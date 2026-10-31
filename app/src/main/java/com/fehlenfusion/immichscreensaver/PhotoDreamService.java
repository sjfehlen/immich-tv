package com.fehlenfusion.immichscreensaver;

import android.service.dreams.DreamService;

/** The screensaver. All slideshow logic lives in {@link Slideshow}. */
public final class PhotoDreamService extends DreamService {
    private Slideshow slideshow;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Prefs prefs = Prefs.load(this);
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(!prefs.dim);
        slideshow = new Slideshow(this, prefs);
        setContentView(slideshow.getView());
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (slideshow != null) slideshow.start();
    }

    @Override
    public void onDreamingStopped() {
        if (slideshow != null) slideshow.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (slideshow != null) slideshow.stop();
        slideshow = null;
        super.onDetachedFromWindow();
    }
}
