package com.fehlenfusion.immichscreensaver;

import android.graphics.Bitmap;

/**
 * Cheap background blur: shrink the photo to a tiny bitmap (~64 px wide), box-blur it in
 * Java, and let the GPU scale it up with bilinear filtering. Costs ~10 KB per photo and
 * no GPU blur passes, which matters on a Chromecast-class GPU.
 */
final class Blur {
    private Blur() {}

    static Bitmap tiny(Bitmap src, int targetWidth, int radius) {
        int w = Math.max(1, Math.min(targetWidth, src.getWidth()));
        int h = Math.max(1, Math.round(src.getHeight() * (float) w / src.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        if (small != src) small.recycle();
        // Three box passes approximate a Gaussian.
        for (int i = 0; i < 3; i++) boxBlur(px, w, h, radius);
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    /** In-place separable box blur of opaque ARGB pixels, edges clamped. */
    static void boxBlur(int[] px, int w, int h, int r) {
        if (r <= 0) return;
        int[] tmp = new int[px.length];
        pass(px, tmp, w, h, r, true);
        pass(tmp, px, w, h, r, false);
    }

    private static void pass(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w;
        int len = horizontal ? w : h;
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int sr = 0, sg = 0, sb = 0;
            for (int k = -r; k <= r; k++) {
                int c = in[index(line, clampI(k, len), w, horizontal)];
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[index(line, i, w, horizontal)] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int cOut = in[index(line, clampI(i - r, len), w, horizontal)];
                int cIn = in[index(line, clampI(i + r + 1, len), w, horizontal)];
                sr += ((cIn >> 16) & 0xFF) - ((cOut >> 16) & 0xFF);
                sg += ((cIn >> 8) & 0xFF) - ((cOut >> 8) & 0xFF);
                sb += (cIn & 0xFF) - (cOut & 0xFF);
            }
        }
    }

    private static int index(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }

    private static int clampI(int v, int len) {
        return v < 0 ? 0 : (v >= len ? len - 1 : v);
    }
}
