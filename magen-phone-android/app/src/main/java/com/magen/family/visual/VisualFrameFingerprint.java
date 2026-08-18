package com.magen.family.visual;

import android.graphics.Bitmap;

/** Cheap 64-bit dHash used only to avoid re-running AI on nearly-identical frames. */
public final class VisualFrameFingerprint {
    private VisualFrameFingerprint() {}

    public static long dHash(Bitmap source) {
        if (source == null || source.isRecycled()) return 0L;
        Bitmap small = Bitmap.createScaledBitmap(source, 9, 8, true);
        long hash = 0L;
        int bit = 0;
        try {
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int a = small.getPixel(x, y);
                    int b = small.getPixel(x + 1, y);
                    if (luma(a) > luma(b)) hash |= (1L << bit);
                    bit++;
                }
            }
            return hash;
        } finally {
            if (small != source && !small.isRecycled()) small.recycle();
        }
    }

    public static int hamming(long a, long b) { return Long.bitCount(a ^ b); }

    private static int luma(int c) {
        int r = (c >> 16) & 0xff;
        int g = (c >> 8) & 0xff;
        int b = c & 0xff;
        return (r * 54 + g * 183 + b * 19) >> 8;
    }
}
