package com.magen.family.visual;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.util.Log;

import com.magen.family.BuildConfig;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;

/**
 * Small local image classifier. No network code belongs in this class.
 * Expected model: GantMan nsfw_mobilenet_v2_140_224, input 224x224 RGB float [0,1]
 * and output labels: drawings, hentai, neutral, porn, sexy.
 *
 * Runtime: Google LiteRT Interpreter API. The model can optionally be hash-pinned at build time.
 */
public final class OnDeviceNsfwClassifier implements AutoCloseable {
    private static final String TAG = "MagenVisualModel";
    public static final String MODEL_ASSET = "nsfw_mobilenet_v2_140_224.tflite";
    private static final int SIZE = 224;
    private static final String[] LABELS = {"drawings", "hentai", "neutral", "porn", "sexy"};

    private final Interpreter interpreter;
    private final ByteBuffer input;
    private final float[][] output = new float[1][5];

    public OnDeviceNsfwClassifier(Context c) throws IOException {
        MappedByteBuffer model = mapAsset(c, MODEL_ASSET);
        verifyModelHashIfPinned(model);
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
        interpreter = new Interpreter(model, options);
        validateModel();
        input = ByteBuffer.allocateDirect(4 * SIZE * SIZE * 3).order(ByteOrder.nativeOrder());
    }

    private void validateModel() throws IOException {
        int[] in = interpreter.getInputTensor(0).shape();
        int[] out = interpreter.getOutputTensor(0).shape();
        if (in.length != 4 || in[0] != 1 || in[1] != SIZE || in[2] != SIZE || in[3] != 3) {
            throw new IOException("unexpected visual model input shape");
        }
        if (interpreter.getInputTensor(0).dataType() != DataType.FLOAT32) {
            throw new IOException("visual model input must be FLOAT32");
        }
        if (out.length != 2 || out[0] != 1 || out[1] != 5 ||
            interpreter.getOutputTensor(0).dataType() != DataType.FLOAT32) {
            throw new IOException("unexpected visual model output");
        }
    }

    public synchronized NsfwResult classify(Bitmap source, int tileIndex) {
        if (source == null || source.isRecycled()) return null;
        Bitmap scaled = Bitmap.createScaledBitmap(source, SIZE, SIZE, true);
        int[] pixels = new int[SIZE * SIZE];
        try {
            scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
        } finally {
            if (scaled != source && !scaled.isRecycled()) scaled.recycle();
        }

        input.rewind();
        for (int pixel : pixels) {
            input.putFloat(((pixel >> 16) & 0xff) / 255.0f);
            input.putFloat(((pixel >> 8) & 0xff) / 255.0f);
            input.putFloat((pixel & 0xff) / 255.0f);
        }
        input.rewind();
        interpreter.run(input, output);

        int best = 0;
        float bestScore = finite(output[0][0]);
        float drawings = finite(output[0][0]);
        float hentai = finite(output[0][1]);
        float neutral = finite(output[0][2]);
        float porn = finite(output[0][3]);
        float sexy = finite(output[0][4]);
        float[] normalized = {drawings, hentai, neutral, porn, sexy};
        for (int i = 1; i < normalized.length; i++) {
            if (normalized[i] > bestScore) { bestScore = normalized[i]; best = i; }
        }
        return new NsfwResult(LABELS[best], bestScore,
            drawings, hentai, neutral, porn, sexy, tileIndex);
    }

    private static float finite(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return 0f;
        return Math.max(0f, Math.min(1f, v));
    }

    private static MappedByteBuffer mapAsset(Context c, String name) throws IOException {
        try (AssetFileDescriptor afd = c.getAssets().openFd(name);
             FileInputStream in = new FileInputStream(afd.getFileDescriptor());
             FileChannel channel = in.getChannel()) {
            return channel.map(FileChannel.MapMode.READ_ONLY, afd.getStartOffset(), afd.getDeclaredLength());
        }
    }

    private static void verifyModelHashIfPinned(MappedByteBuffer model) throws IOException {
        String expected = BuildConfig.VISUAL_MODEL_SHA256 == null ? "" : BuildConfig.VISUAL_MODEL_SHA256.trim().toLowerCase();
        if (expected.isEmpty()) {
            Log.w(TAG, "Visual model hash is not pinned in this build");
            return;
        }
        if (!expected.matches("[0-9a-f]{64}")) throw new IOException("invalid pinned visual model SHA-256");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            ByteBuffer dup = model.asReadOnlyBuffer();
            dup.position(0);
            byte[] buf = new byte[64 * 1024];
            while (dup.hasRemaining()) {
                int n = Math.min(dup.remaining(), buf.length);
                dup.get(buf, 0, n);
                md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest()) sb.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
            if (!expected.equals(sb.toString())) throw new IOException("visual model SHA-256 mismatch");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("could not verify visual model hash", e);
        }
    }

    @Override public synchronized void close() {
        try { interpreter.close(); } catch (Exception e) { Log.w(TAG, "close: " + e.getMessage()); }
    }
}
