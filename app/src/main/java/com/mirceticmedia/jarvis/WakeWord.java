package com.mirceticmedia.jarvis;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Collections;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * "Hey Jarvis" detector - the same openWakeWord models the laptop uses, run on the phone.
 * Feed it 1280 samples (80 ms, 16 kHz, 16-bit mono) at a time; it returns a score 0..1.
 * (Port of openwakeword's streaming pipeline: melspectrogram -> speech embedding -> wake word model.)
 */
public class WakeWord implements AutoCloseable {
    public static final int CHUNK = 1280;
    private static final int RAW_KEEP = CHUNK + 480;     // 80 ms + 3 frames of look-back
    private static final int MEL_WINDOW = 76;
    private static final int MEL_MAX = 970;
    private static final int FEATURES = 16;

    private final OrtEnvironment env;
    private final OrtSession mel, emb, ww;
    private final short[] raw = new short[RAW_KEEP];
    private final ArrayDeque<float[]> melFrames = new ArrayDeque<>();
    private final ArrayDeque<float[]> features = new ArrayDeque<>();
    private int chunks = 0;

    public WakeWord(Context ctx) throws Exception {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(1);
        mel = env.createSession(asset(ctx, "oww/melspectrogram.onnx"), o);
        emb = env.createSession(asset(ctx, "oww/embedding_model.onnx"), o);
        ww = env.createSession(asset(ctx, "oww/hey_jarvis_v0.1.onnx"), o);
        reset();
    }

    private static byte[] asset(Context ctx, String name) throws Exception {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    public final void reset() {
        java.util.Arrays.fill(raw, (short) 0);
        melFrames.clear();
        for (int i = 0; i < MEL_WINDOW; i++) {
            float[] ones = new float[32];
            java.util.Arrays.fill(ones, 1f);
            melFrames.add(ones);
        }
        features.clear();
        chunks = 0;
    }

    /** @param pcm exactly CHUNK samples */
    public float process(short[] pcm) throws Exception {
        // keep the last 1760 samples
        System.arraycopy(raw, CHUNK, raw, 0, RAW_KEEP - CHUNK);
        System.arraycopy(pcm, 0, raw, RAW_KEEP - CHUNK, CHUNK);

        // 1) melspectrogram of the newest audio
        float[] in = new float[RAW_KEEP];
        for (int i = 0; i < RAW_KEEP; i++) in[i] = raw[i];
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(in), new long[]{1, RAW_KEEP});
             OrtSession.Result r = mel.run(Collections.singletonMap("input", t))) {
            float[][][][] spec = (float[][][][]) r.get(0).getValue();     // [time=1][1][frames][32]
            for (float[] frame : spec[0][0]) {
                float[] f = new float[32];
                for (int k = 0; k < 32; k++) f[k] = frame[k] / 10f + 2f;
                melFrames.add(f);
            }
        }
        while (melFrames.size() > MEL_MAX) melFrames.removeFirst();

        // 2) speech embedding of the last 76 mel frames
        float[] window = new float[MEL_WINDOW * 32];
        Object[] frames = melFrames.toArray();
        int start = frames.length - MEL_WINDOW;
        for (int i = 0; i < MEL_WINDOW; i++) System.arraycopy((float[]) frames[start + i], 0, window, i * 32, 32);
        float[] e = new float[96];
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(window), new long[]{1, MEL_WINDOW, 32, 1});
             OrtSession.Result r = emb.run(Collections.singletonMap("input_1", t))) {
            float[][][][] out = (float[][][][]) r.get(0).getValue();      // [1][1][1][96]
            System.arraycopy(out[0][0][0], 0, e, 0, 96);
        }
        features.add(e);
        while (features.size() > FEATURES) features.removeFirst();
        chunks++;
        if (features.size() < FEATURES || chunks <= 5) return 0f;

        // 3) wake word score from the last 16 embeddings
        float[] feat = new float[FEATURES * 96];
        int i = 0;
        for (float[] f : features) { System.arraycopy(f, 0, feat, i * 96, 96); i++; }
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(feat), new long[]{1, FEATURES, 96});
             OrtSession.Result r = ww.run(Collections.singletonMap("x.1", t))) {
            float[][] out = (float[][]) r.get(0).getValue();
            return out[0][0];
        }
    }

    @Override
    public void close() {
        try { mel.close(); } catch (Exception ignored) { }
        try { emb.close(); } catch (Exception ignored) { }
        try { ww.close(); } catch (Exception ignored) { }
    }
}
