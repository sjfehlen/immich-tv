package com.fehlenfusion.immichscreensaver;

import android.service.dreams.DreamService;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * The screensaver. Slideshow logic lives in {@link Slideshow}.
 *
 * While music (or any media) is playing, a Now playing card shows bottom-right and the remote
 * controls it without waking the TV: Left = previous, Right = next, OK = play/pause. Any other
 * key ends the screensaver, as usual; volume keys keep working.
 */
public final class PhotoDreamService extends DreamService {
    private Slideshow slideshow;
    private NowPlaying nowPlaying;
    private NowPlayingView npView;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Prefs prefs = Prefs.load(this);
        // Interactive so media keys can be handled; every other key ends the dream (dispatchKeyEvent).
        setInteractive(true);
        setFullscreen(true);
        setScreenBright(!prefs.dim);
        slideshow = new Slideshow(this, prefs);

        FrameLayout root = new FrameLayout(this);
        root.addView(slideshow.getView(), new FrameLayout.LayoutParams(-1, -1));
        nowPlaying = new NowPlaying(this, this::onNowPlaying);
        npView = new NowPlayingView(this, false, nowPlaying);
        npView.setVisibility(View.GONE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        lp.rightMargin = Math.round(dm.widthPixels * 0.05f);
        lp.bottomMargin = Math.round(dm.heightPixels * 0.05f);
        root.addView(npView, lp);
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (slideshow != null) slideshow.start();
        if (nowPlaying != null) nowPlaying.start();
    }

    @Override
    public void onDreamingStopped() {
        if (nowPlaying != null) nowPlaying.stop();
        if (slideshow != null) slideshow.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (nowPlaying != null) nowPlaying.stop();
        if (npView != null) npView.clear();
        if (slideshow != null) slideshow.stop();
        slideshow = null;
        nowPlaying = null;
        npView = null;
        super.onDetachedFromWindow();
    }

    private void onNowPlaying(NowPlaying.Info info) {
        if (npView == null) return;
        if (info == null) {
            npView.setVisibility(View.GONE);
            npView.clear();
            if (slideshow != null) slideshow.setCaptionHidden(false);
        } else {
            npView.bind(info);
            npView.setVisibility(View.VISIBLE);
            if (slideshow != null) slideshow.setCaptionHidden(true);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event);
        }
        boolean media = nowPlaying != null && nowPlaying.info() != null;
        if (media) {
            Runnable action = null;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    action = nowPlaying::previous;
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    action = nowPlaying::next;
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    action = nowPlaying::playPause;
                    break;
                default:
                    break;
            }
            if (action != null) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) action.run();
                return true;
            }
        }
        // Any other key: leave the screensaver, like a non-interactive dream would.
        if (event.getAction() == KeyEvent.ACTION_DOWN) finish();
        return true;
    }
}
