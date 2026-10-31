package com.fehlenfusion.immichscreensaver;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/** Full-screen preview of the screensaver from the settings screen. Any button exits. */
public final class PreviewActivity extends Activity {
    private Slideshow slideshow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        slideshow = new Slideshow(this, Prefs.load(this));
        setContentView(slideshow.getView());
    }

    @Override
    protected void onStart() {
        super.onStart();
        slideshow.start();
    }

    @Override
    protected void onStop() {
        slideshow.stop();
        super.onStop();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.onKeyDown(keyCode, event);
        }
        finish();
        return true;
    }
}
