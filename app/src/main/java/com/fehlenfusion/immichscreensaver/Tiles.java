package com.fehlenfusion.immichscreensaver;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * App tiles in the style of the Apple TV home screen: no outline; the focused tile rises,
 * grows, casts a large soft shadow, catches a soft gloss, and briefly tilts toward the
 * direction you moved from before settling flat.
 */
final class Tiles {
    static final int BANNER_W_DP = 112;
    static final int BANNER_H_DP = 63;
    static final float FOCUS_SCALE = 1.18f;
    private static final float TILT_DEG = 7f;

    /** Last D-pad key on the home/all-apps screens, so a newly focused tile can tilt toward it. */
    static int lastNavKey;

    private Tiles() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** A 16:10-ish banner tile. Apps without a banner get their icon and name on a dark card. */
    static View appTile(Context c, AppsRepo.App app) {
        FrameLayout tile = baseTile(c);
        tile.setLongClickable(true);
        tile.setContentDescription(app.label);
        if (app.banner != null) {
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(app.banner);
            tile.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF1E2433);
            tile.setBackground(bg);
            LinearLayout inner = new LinearLayout(c);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER);
            if (app.icon != null) {
                ImageView iv = new ImageView(c);
                iv.setImageDrawable(app.icon);
                inner.addView(iv, new LinearLayout.LayoutParams(dp(c, 36), dp(c, 36)));
            }
            TextView t = label(c, app.label, 12, Color.WHITE);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setPadding(dp(c, 6), dp(c, 4), dp(c, 6), 0);
            inner.addView(t);
            tile.addView(inner, new FrameLayout.LayoutParams(-1, -1));
        }
        return tile;
    }

    /**
     * Settings as an ordinary tile at the end of the row, muted like tvOS's Settings icon:
     * a dark translucent card with a dim gear, so it does not compete with the apps.
     */
    static View settingsTile(Context c) {
        FrameLayout tile = baseTile(c);
        tile.setContentDescription("Settings and more");
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x59303540, 0x59181B22});
        tile.setBackground(bg);
        TextView gear = label(c, "⚙", 26, 0xB3FFFFFF);
        gear.setShadowLayer(0, 0, 0, 0);
        gear.setGravity(Gravity.CENTER);
        tile.addView(gear, new FrameLayout.LayoutParams(-1, -1));
        return tile;
    }

    /** Rounded, clipped, focusable frame with the Apple-TV-style focus behaviour. */
    private static FrameLayout baseTile(final Context c) {
        final FrameLayout tile = new FrameLayout(c);
        tile.setFocusable(true);
        tile.setClickable(true);
        final float radius = dp(c, 10);
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        tile.setClipToOutline(true);
        tile.setCameraDistance(8000 * c.getResources().getDisplayMetrics().density);

        // Soft gloss: light falling on the top of the tile, fading out before the middle.
        final GradientDrawable gloss = new GradientDrawable();
        gloss.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        gloss.setColors(new int[]{0x38FFFFFF, 0x10FFFFFF, 0x00FFFFFF});
        gloss.setGradientCenter(0.35f, 0f);
        gloss.setGradientRadius(dp(c, BANNER_W_DP) * 0.75f);
        gloss.setCornerRadius(radius);

        tile.setOnFocusChangeListener((v, has) -> {
            ((FrameLayout) v).setForeground(has ? gloss : null);
            if (has) {
                // Lean in from the direction we arrived from, then settle flat (tvOS "tilt").
                float tilt = lastNavKey == KeyEvent.KEYCODE_DPAD_RIGHT ? -TILT_DEG
                        : lastNavKey == KeyEvent.KEYCODE_DPAD_LEFT ? TILT_DEG : 0f;
                v.setRotationY(tilt);
                v.animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE).rotationY(0f)
                        .translationY(-dp(c, 6))
                        .setInterpolator(new OvershootInterpolator(1.1f))
                        .setDuration(320);
                v.setElevation(dp(c, 22));
            } else {
                v.animate().scaleX(1f).scaleY(1f).rotationY(0f).translationY(0f)
                        .setInterpolator(new DecelerateInterpolator())
                        .setDuration(180);
                v.setElevation(0);
            }
        });
        return tile;
    }

    static TextView label(Context c, String s, int sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setShadowLayer(6f, 0f, 1f, 0x99000000);
        return t;
    }
}
