package com.fehlenfusion.immichscreensaver;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * The full-screen backdrop behind Settings and All apps: the home gradient, then the album
 * cover blurred to a tiny bitmap fading in over it, and a shade that is darker on the left.
 * Cards on top only need a translucent tint to read as frosted glass.
 */
final class Backdrop {
    private final Activity activity;
    final ImageView image;
    private Bitmap bitmap;
    private String loadedFor;

    /** Adds the gradient, photo and shade to {@code root} (call first, so they sit underneath). */
    Backdrop(Activity activity, FrameLayout root) {
        this.activity = activity;
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF262B3D, 0xFF10131C}));
        image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setAlpha(0f);
        root.addView(image, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(activity);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x99080B14, 0x59080B14, 0x14080B14}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));
    }

    /** Loads the album cover at thumbnail size and blurs it; reloads only if the album changed. */
    void load() {
        final Config c = Config.load(activity);
        if (!c.isComplete()) return;
        String key = c.serverUrl + "|" + c.albumIds.get(0);
        if (key.equals(loadedFor)) return;
        loadedFor = key;
        new Thread(() -> {
            Bitmap blurred = null;
            try {
                ImmichClient client = new ImmichClient(c.serverUrl, c.apiKey);
                String cover = client.fetchAlbumCoverId(c.albumIds.get(0));
                if (cover != null) {
                    Bitmap small = client.fetchImage(cover, "thumbnail", 256, 256, Bitmap.Config.RGB_565);
                    if (small != null) {
                        blurred = Blur.tiny(small, 48, 3);
                        small.recycle();
                    }
                }
            } catch (Throwable ignored) {
                // Decoration only.
            }
            final Bitmap result = blurred;
            activity.runOnUiThread(() -> {
                if (result == null) return;
                if (activity.isFinishing() || activity.isDestroyed()) {
                    result.recycle();
                    return;
                }
                image.setImageBitmap(result);
                if (bitmap != null) bitmap.recycle();
                bitmap = result;
                image.animate().alpha(1f).setDuration(800);
            });
        }, "immich-backdrop").start();
    }

    void release() {
        image.setImageDrawable(null);
        if (bitmap != null) bitmap.recycle();
        bitmap = null;
    }
}
