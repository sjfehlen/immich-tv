package com.fehlenfusion.immichscreensaver;

import android.app.Activity;
import android.os.Bundle;

/** Invisible; closes at once so the window behind it sends a window-change event (see HomeRedirectService). */
public final class ProbeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
        overridePendingTransition(0, 0);
    }
}
