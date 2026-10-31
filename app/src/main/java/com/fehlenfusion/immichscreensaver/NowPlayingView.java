package com.fehlenfusion.immichscreensaver;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Compact "Now playing" card in a liquid-glass style: the photo behind it, blurred, under a dark
 * tint with a top sheen and a light rim. On the home screen it has previous / play-pause / next
 * buttons; in the screensaver it shows a key hint instead.
 *
 * The blur is a snapshot: the backdrop source is drawn at 1/8 scale, cropped to the card and
 * box-blurred (~10 KB), refreshed when the photo changes. No per-frame GPU blur.
 */
final class NowPlayingView extends LinearLayout {
    private static final int SNAPSHOT_SCALE = 8;

    private final ImageView art;
    private final TextView title;
    private final TextView artist;
    private final ImageView playPause;
    private final TextView hint;
    private final float radius;
    private View backdropSource;
    private Bitmap glass;

    NowPlayingView(Context c, boolean controls, final NowPlaying np) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int pad = Tiles.dp(c, 9);
        setPadding(pad, pad, Tiles.dp(c, controls ? 10 : 14), pad);
        radius = Tiles.dp(c, 16);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setClipToOutline(true);    // rounded glass edges
        setClipToPadding(false);   // the focused button's zoom may reach into the padding
        setClipChildren(false);
        setBackground(glassLayers(null)); // tint-only until the first snapshot

        art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackgroundColor(0x33FFFFFF);
        final float r = Tiles.dp(c, 8);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        art.setClipToOutline(true);
        int a = Tiles.dp(c, 48);
        addView(art, new LayoutParams(a, a));
        if (controls) {
            // Album cover opens the playing app (e.g. Spotify's player).
            art.setFocusable(true);
            art.setClickable(true);
            art.setContentDescription("Open player");
            art.setOnClickListener(v -> np.openApp(c));
            final GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(r);
            ring.setColor(Color.TRANSPARENT);
            ring.setStroke(Math.max(2, Tiles.dp(c, 3)), Color.WHITE);
            art.setOnFocusChangeListener((v, has) -> {
                if (android.os.Build.VERSION.SDK_INT >= 23) v.setForeground(has ? ring : null);
                v.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).setDuration(140);
                v.setElevation(has ? Tiles.dp(c, 8) : 0);
            });
        }

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(VERTICAL);
        text.setPadding(Tiles.dp(c, 11), 0, Tiles.dp(c, 6), 0);
        title = Tiles.label(c, "", 14, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        artist = Tiles.label(c, "", 12, 0xD9FFFFFF);
        for (TextView t : new TextView[]{title, artist}) {
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setShadowLayer(4f, 0f, 1f, 0x66000000);
            t.setMaxWidth(Tiles.dp(c, 180));
            text.addView(t);
        }
        hint = Tiles.label(c, "", 10, 0xB3FFFFFF);
        hint.setShadowLayer(4f, 0f, 1f, 0x66000000);
        hint.setPadding(0, Tiles.dp(c, 2), 0, 0);
        hint.setVisibility(controls ? GONE : VISIBLE);
        text.addView(hint);
        addView(text, new LayoutParams(-2, -2));

        if (controls) {
            addView(button(c, R.drawable.ic_np_prev, v -> np.previous()));
            playPause = button(c, R.drawable.ic_np_pause, v -> np.playPause());
            addView(playPause);
            addView(button(c, R.drawable.ic_np_next, v -> np.next()));
        } else {
            playPause = null;
        }
    }

    /** The view whose pixels sit behind this card (the photo layer). */
    void setBackdropSource(View source) {
        backdropSource = source;
        post(this::refreshBackdrop);
    }

    void bind(NowPlaying.Info info) {
        title.setText(info.title);
        artist.setText(info.artist == null ? "" : info.artist);
        artist.setVisibility(info.artist == null ? GONE : VISIBLE);
        art.setImageBitmap(info.art); // session-owned; never recycled here
        if (playPause != null) {
            playPause.setImageResource(info.playing ? R.drawable.ic_np_pause : R.drawable.ic_np_play);
            playPause.setContentDescription(info.playing ? "Pause" : "Play");
        }
        hint.setText(info.playing ? "◀ ▶ skip  ·  OK pause" : "◀ ▶ skip  ·  OK play");
        post(this::refreshBackdrop);
    }

    void clear() {
        art.setImageDrawable(null);
        setBackground(glassLayers(null));
        if (glass != null) glass.recycle();
        glass = null;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        post(this::refreshBackdrop);
    }

    /** Re-snapshot the blurred backdrop (call when the photo behind changes). */
    void refreshBackdrop() {
        View src = backdropSource;
        if (src == null || getVisibility() != VISIBLE || getWidth() == 0 || src.getWidth() == 0) return;
        int[] me = new int[2], bg = new int[2];
        getLocationInWindow(me);
        src.getLocationInWindow(bg);
        int bw = Math.max(1, getWidth() / SNAPSHOT_SCALE), bh = Math.max(1, getHeight() / SNAPSHOT_SCALE);
        Bitmap snap;
        try {
            snap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(snap);
            cv.drawColor(0xFF262B3D); // stand-in for the home gradient where no photo is showing
            cv.scale(1f / SNAPSHOT_SCALE, 1f / SNAPSHOT_SCALE);
            cv.translate(bg[0] - me[0], bg[1] - me[1]);
            src.draw(cv);
            int[] px = new int[bw * bh];
            snap.getPixels(px, 0, bw, 0, 0, bw, bh);
            for (int i = 0; i < 2; i++) Blur.boxBlur(px, bw, bh, 2);
            snap.setPixels(px, 0, bw, 0, 0, bw, bh);
        } catch (RuntimeException | OutOfMemoryError e) {
            return; // keep the tint-only glass
        }
        BitmapDrawable bd = new BitmapDrawable(getResources(), snap);
        bd.setFilterBitmap(true);
        setBackground(glassLayers(bd));
        if (glass != null) glass.recycle();
        glass = snap;
    }

    /** Blurred backdrop (optional) + dark tint + top sheen + light rim. */
    private Drawable glassLayers(Drawable blurred) {
        GradientDrawable tint = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{blurred == null ? 0xB3202430 : 0x59141820, blurred == null ? 0xCC181B22 : 0x73101318});
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x40FFFFFF, 0x0DFFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        GradientDrawable rim = new GradientDrawable();
        rim.setColor(Color.TRANSPARENT);
        rim.setStroke(Math.max(1, Tiles.dp(getContext(), 1)), 0x4DFFFFFF);
        for (GradientDrawable g : new GradientDrawable[]{tint, sheen, rim}) g.setCornerRadius(radius);
        return blurred == null
                ? new LayerDrawable(new Drawable[]{tint, sheen, rim})
                : new LayerDrawable(new Drawable[]{blurred, tint, sheen, rim});
    }

    /** Small glass button: clear until focused, then a frosted disc with a light rim. */
    private static ImageView button(Context c, int icon, OnClickListener l) {
        ImageView b = new ImageView(c);
        b.setImageResource(icon);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(l);
        final GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.OVAL);
        normal.setColor(Color.TRANSPARENT);
        final GradientDrawable focused = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x66FFFFFF, 0x33FFFFFF});
        focused.setShape(GradientDrawable.OVAL);
        focused.setStroke(Math.max(1, Tiles.dp(c, 1)), 0x99FFFFFF);
        b.setBackground(normal);
        b.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? focused : normal);
            v.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).setDuration(140);
        });
        int s = Tiles.dp(c, 36);
        LayoutParams lp = new LayoutParams(s, s);
        lp.leftMargin = Tiles.dp(c, 2);
        b.setLayoutParams(lp);
        return b;
    }
}
