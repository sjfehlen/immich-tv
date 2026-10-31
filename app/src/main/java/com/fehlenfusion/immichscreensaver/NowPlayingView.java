package com.fehlenfusion.immichscreensaver;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Compact "Now playing" card in the home screen's shared style: the frosted {@link Frost}
 * material, the tile corner radius, the app-tile height, and the same focus motion as the app
 * tiles (no outlines). On the home screen it has previous / play-pause / next buttons; in the
 * screensaver it shows a key hint instead.
 */
final class NowPlayingView extends LinearLayout {
    private final ImageView art;
    private final TextView title;
    private final TextView artist;
    private final ImageView playPause;
    private final TextView hint;
    private final Frost frost;

    NowPlayingView(Context c, boolean controls, final NowPlaying np) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        // Compact: 36 dp art + 6 dp padding, sitting under the clock.
        int pad = Tiles.dp(c, 6);
        setPadding(pad, pad, Tiles.dp(c, controls ? 6 : 14), pad);
        float radius = Tiles.dp(c, Tiles.RADIUS_DP);
        Tiles.rounded(this, radius);
        setClipToPadding(false);   // the focused button's zoom may reach into the padding
        setClipChildren(false);
        frost = new Frost(this, radius);

        art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackgroundColor(0x33FFFFFF);
        Tiles.rounded(art, Tiles.dp(c, 5));
        int a = Tiles.dp(c, 36);
        addView(art, new LayoutParams(a, a));
        if (controls) {
            // Album cover opens the playing app (e.g. Spotify's player). Focus like an app tile.
            art.setFocusable(true);
            art.setClickable(true);
            art.setContentDescription("Open player");
            art.setOnClickListener(v -> np.openApp(c));
            final android.graphics.drawable.Drawable gloss = Tiles.gloss(c, Tiles.dp(c, 5), a);
            art.setOnFocusChangeListener((v, has) -> {
                if (android.os.Build.VERSION.SDK_INT >= 23) v.setForeground(has ? gloss : null);
                Tiles.focusMotion(v, has, Tiles.FOCUS_SCALE, 0, Tiles.dp(c, 10));
            });
        }

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(VERTICAL);
        text.setPadding(Tiles.dp(c, 10), 0, Tiles.dp(c, 6), 0);
        title = Tiles.label(c, "", 13, Color.WHITE);
        title.setTypeface(Tiles.MEDIUM);
        artist = Tiles.label(c, "", 11, 0xB3FFFFFF);
        for (TextView t : new TextView[]{title, artist}) {
            t.setSingleLine(true);
            t.setIncludeFontPadding(false);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setMaxWidth(Tiles.dp(c, 150));
            text.addView(t);
        }
        // Long song names scroll (marquee) instead of being cut off. A marquee only runs while
        // the view is selected, so keep it selected; it doesn't take focus from the buttons.
        title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        title.setHorizontalFadingEdgeEnabled(true);
        title.setFadingEdgeLength(Tiles.dp(c, 12));
        title.setSelected(true);
        hint = Tiles.label(c, "", 10, 0x99FFFFFF);
        hint.setPadding(0, Tiles.dp(c, 2), 0, 0);
        hint.setVisibility(controls ? GONE : VISIBLE);
        text.addView(hint);
        addView(text, new LayoutParams(-2, -2));

        if (controls) {
            // Pushes the buttons to the right edge when the card is stretched to the clock's width.
            addView(new android.widget.Space(c), new LayoutParams(0, 0, 1f));
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
        frost.setSource(source);
    }

    void bind(NowPlaying.Info info) {
        if (!TextUtils.equals(title.getText(), info.title)) title.setText(info.title); // don't restart the marquee
        artist.setText(info.artist == null ? "" : info.artist);
        artist.setVisibility(info.artist == null ? GONE : VISIBLE);
        art.setImageBitmap(info.art); // session-owned; never recycled here
        if (playPause != null) {
            playPause.setImageResource(info.playing ? R.drawable.ic_np_pause : R.drawable.ic_np_play);
            playPause.setContentDescription(info.playing ? "Pause" : "Play");
        }
        hint.setText(info.playing ? "◀ ▶ skip  ·  OK pause" : "◀ ▶ skip  ·  OK play");
        frost.refreshSoon();
    }

    void clear() {
        art.setImageDrawable(null);
        frost.clear();
    }

    /** Re-snapshot the frosted backdrop (call when the photo behind changes). */
    void refreshBackdrop() {
        frost.refresh();
    }

    /**
     * Small round button: just the glyph until focused, then (like tvOS) a white disc with a
     * dark glyph that grows slightly. No outline.
     */
    private static ImageView button(Context c, int icon, OnClickListener l) {
        ImageView b = new ImageView(c);
        b.setImageResource(icon);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(l);
        final GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(0xF2FFFFFF);
        b.setOnFocusChangeListener((v, has) -> {
            ImageView iv = (ImageView) v;
            iv.setBackground(has ? disc : null);
            iv.setColorFilter(has ? 0xFF15181F : Color.WHITE);
            Tiles.focusMotion(v, has, 1.12f, 0, has ? Tiles.dp(c, 6) : 0);
        });
        b.setColorFilter(Color.WHITE);
        int s = Tiles.dp(c, 30);
        LayoutParams lp = new LayoutParams(s, s);
        lp.leftMargin = Tiles.dp(c, 2);
        b.setLayoutParams(lp);
        return b;
    }
}
