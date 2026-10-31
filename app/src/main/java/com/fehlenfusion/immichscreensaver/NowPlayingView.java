package com.fehlenfusion.immichscreensaver;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * "Now playing" card: album art, title, artist and, on the home screen, focusable
 * previous / play-pause / next buttons. In the screensaver it shows a key hint instead.
 */
final class NowPlayingView extends LinearLayout {
    private final ImageView art;
    private final TextView title;
    private final TextView artist;
    private final ImageView playPause;
    private final TextView hint;

    NowPlayingView(Context c, boolean controls, final NowPlaying np) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int pad = Tiles.dp(c, 12);
        setPadding(pad, pad, Tiles.dp(c, controls ? 16 : 18), pad);
        setClipChildren(false); // the focused button grows 15%
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE01C1F26); // nearly opaque: readable on any photo
        bg.setCornerRadius(Tiles.dp(c, 18));
        setBackground(bg);

        art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackgroundColor(0xFF2A2E37);
        final float r = Tiles.dp(c, 10);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        art.setClipToOutline(true);
        int a = Tiles.dp(c, 64);
        addView(art, new LayoutParams(a, a));

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(VERTICAL);
        text.setPadding(Tiles.dp(c, 14), 0, Tiles.dp(c, 8), 0);
        title = Tiles.label(c, "", 16, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        artist = Tiles.label(c, "", 13, 0xCCFFFFFF);
        for (TextView t : new TextView[]{title, artist}) {
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setShadowLayer(0, 0, 0, 0);
            t.setMaxWidth(Tiles.dp(c, 220));
            text.addView(t);
        }
        hint = Tiles.label(c, "◀ ▶  skip    OK  play / pause", 11, 0x99FFFFFF);
        hint.setShadowLayer(0, 0, 0, 0);
        hint.setPadding(0, Tiles.dp(c, 4), 0, 0);
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

    void bind(NowPlaying.Info info) {
        title.setText(info.title);
        artist.setText(info.artist == null ? "" : info.artist);
        artist.setVisibility(info.artist == null ? GONE : VISIBLE);
        art.setImageBitmap(info.art); // session-owned; never recycled here
        if (playPause != null) {
            playPause.setImageResource(info.playing ? R.drawable.ic_np_pause : R.drawable.ic_np_play);
            playPause.setContentDescription(info.playing ? "Pause" : "Play");
        }
        if (!info.playing) hint.setText("◀ ▶  skip    OK  play");
        else hint.setText("◀ ▶  skip    OK  pause");
    }

    void clear() {
        art.setImageDrawable(null);
    }

    /** Round glass button; brightens and grows slightly when focused. */
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
        final GradientDrawable focused = new GradientDrawable();
        focused.setShape(GradientDrawable.OVAL);
        focused.setColor(0x4DFFFFFF);
        b.setBackground(normal);
        b.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? focused : normal);
            v.animate().scaleX(has ? 1.15f : 1f).scaleY(has ? 1.15f : 1f).setDuration(140);
        });
        int s = Tiles.dp(c, 44);
        LayoutParams lp = new LayoutParams(s, s);
        lp.leftMargin = Tiles.dp(c, 4);
        b.setLayoutParams(lp);
        return b;
    }
}
