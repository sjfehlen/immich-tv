package com.fehlenfusion.immichscreensaver;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;

/**
 * The home screen's one "chrome" material (now playing card, settings tile): the photo behind
 * the view, blurred, under a dark tint. No rim, no sheen, so it sits with the plain app tiles.
 *
 * The blur is a snapshot: the backdrop source is drawn at 1/8 scale, cropped to the view and
 * box-blurred (a few KB), refreshed when the photo or the view's position changes. No per-frame
 * GPU blur.
 */
final class Frost {
    private static final int SCALE = 8;

    private final View target;
    private final float radius;
    private View source;
    private Bitmap snapshot;
    private final Runnable refresh = this::refresh;

    Frost(View target, float radius) {
        this.target = target;
        this.radius = radius;
        target.setBackground(layers(null)); // tint-only until the first snapshot
        target.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> refreshSoon());
    }

    /** The view whose pixels sit behind the target (the photo layer). */
    void setSource(View source) {
        this.source = source;
        refreshSoon();
    }

    /** Coalesces bursts (scrolling, layout passes) into one snapshot. */
    void refreshSoon() {
        target.removeCallbacks(refresh);
        target.postDelayed(refresh, 60);
    }

    void refresh() {
        View src = source;
        if (src == null || target.getVisibility() != View.VISIBLE || target.getWidth() == 0
                || src.getWidth() == 0 || !(target.getParent() instanceof View)) return;
        // Untransformed position (parent + layout offset), so a focus zoom doesn't shift the crop.
        int[] parent = new int[2], bg = new int[2];
        ((View) target.getParent()).getLocationInWindow(parent);
        src.getLocationInWindow(bg);
        int x = parent[0] + target.getLeft(), y = parent[1] + target.getTop();
        int bw = Math.max(1, target.getWidth() / SCALE), bh = Math.max(1, target.getHeight() / SCALE);
        Bitmap snap;
        try {
            snap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(snap);
            cv.drawColor(0xFF262B3D); // stand-in for the home gradient where no photo is showing
            cv.scale(1f / SCALE, 1f / SCALE);
            cv.translate(bg[0] - x, bg[1] - y);
            src.draw(cv);
            int[] px = new int[bw * bh];
            snap.getPixels(px, 0, bw, 0, 0, bw, bh);
            for (int i = 0; i < 2; i++) Blur.boxBlur(px, bw, bh, 2);
            snap.setPixels(px, 0, bw, 0, 0, bw, bh);
        } catch (RuntimeException | OutOfMemoryError e) {
            return; // keep the tint-only frost
        }
        BitmapDrawable bd = new BitmapDrawable(target.getResources(), snap);
        bd.setFilterBitmap(true);
        target.setBackground(layers(bd));
        if (snapshot != null) snapshot.recycle();
        snapshot = snap;
    }

    void clear() {
        target.removeCallbacks(refresh);
        target.setBackground(layers(null));
        if (snapshot != null) snapshot.recycle();
        snapshot = null;
    }

    private Drawable layers(Drawable blurred) {
        GradientDrawable tint = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                blurred == null ? new int[]{0xB3202430, 0xCC181B22} : new int[]{0x40141820, 0x59101318});
        tint.setCornerRadius(radius);
        return blurred == null ? tint : new LayerDrawable(new Drawable[]{blurred, tint});
    }
}
