package com.fehlenfusion.immichscreensaver;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** App tiles and shortcut pills for the home screen. Focus = quick scale + white outline. */
final class Tiles {
    static final int BANNER_W_DP = 112;
    static final int BANNER_H_DP = 63;

    private Tiles() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** A 16:9 banner tile. Apps without a banner get their icon and name on a dark card. */
    static View appTile(Context c, AppsRepo.App app) {
        final FrameLayout tile = new FrameLayout(c);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setLongClickable(true);
        tile.setContentDescription(app.label);
        final float radius = dp(c, 12);
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        tile.setClipToOutline(true);

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
                inner.addView(iv, new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)));
            }
            TextView t = label(c, app.label, 13, Color.WHITE);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setPadding(dp(c, 6), dp(c, 4), dp(c, 6), 0);
            inner.addView(t);
            tile.addView(inner, new FrameLayout.LayoutParams(-1, -1));
        }

        // Focus ring drawn as the foreground so it sits above the banner.
        final GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius);
        ring.setStroke(dp(c, 3), Color.WHITE);
        ring.setColor(Color.TRANSPARENT);
        tile.setOnFocusChangeListener((v, has) -> {
            ((FrameLayout) v).setForeground(has ? ring : null);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(120);
            v.setElevation(has ? dp(c, 8) : 0);
        });
        return tile;
    }

    /** Rounded translucent pill used for the shortcut row. */
    static View pill(Context c, String glyph, String text) {
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setGravity(Gravity.CENTER);
        p.setFocusable(true);
        p.setClickable(true);
        final GradientDrawable normal = new GradientDrawable();
        normal.setColor(0x33FFFFFF);
        normal.setStroke(dp(c, 1), 0x22FFFFFF);
        normal.setCornerRadius(dp(c, 14));
        final GradientDrawable focused = new GradientDrawable();
        focused.setColor(0x66FFFFFF);
        focused.setStroke(dp(c, 2), Color.WHITE);
        focused.setCornerRadius(dp(c, 14));
        p.setBackground(normal);
        for (TextView t : new TextView[]{label(c, glyph, 20, Color.WHITE), label(c, text, 15, Color.WHITE)}) {
            t.setGravity(Gravity.CENTER);
            p.addView(t, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        p.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? focused : normal);
            v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(120);
        });
        return p;
    }

    /** Small round icon button (the home screen's settings button). */
    static View roundButton(Context c, String glyph) {
        TextView b = label(c, glyph, 24, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        final GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.OVAL);
        normal.setColor(0x4D000000);
        normal.setStroke(dp(c, 1), 0x66FFFFFF);
        final GradientDrawable focused = new GradientDrawable();
        focused.setShape(GradientDrawable.OVAL);
        focused.setColor(0x80FFFFFF);
        focused.setStroke(dp(c, 2), Color.WHITE);
        b.setBackground(normal);
        b.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? focused : normal);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(120);
        });
        return b;
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
