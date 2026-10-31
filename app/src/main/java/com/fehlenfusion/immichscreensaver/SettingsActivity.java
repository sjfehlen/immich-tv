package com.fehlenfusion.immichscreensaver;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Launcher entry: D-pad settings list over a blurred photo from the selected album.
 * Selecting a row cycles its value; changes apply the next time the screensaver starts.
 */
public final class SettingsActivity extends Activity {
    private static final String DREAM_COMPONENT =
            "com.fehlenfusion.immichscreensaver/.PhotoDreamService";
    private static final String DREAM_COMPONENT_LONG =
            "com.fehlenfusion.immichscreensaver/com.fehlenfusion.immichscreensaver.PhotoDreamService";

    private Prefs prefs;
    private ImageView backdrop;
    private Bitmap backdropBitmap;
    private boolean backdropRequested;
    private TextView subtitle;
    private TextView footer;
    private TextView homeValue, albumsValue, serverValue, orderValue, durationValue, styleValue, zoomValue, clockValue, infoValue, dimValue;
    private View previewRow, albumsRow, serverRow;
    private String backdropFor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
    }

    @Override
    protected void onResume() {
        super.onResume();
        prefs = Prefs.load(this);
        refresh();
        loadBackdrop();
    }

    @Override
    protected void onDestroy() {
        backdrop.setImageDrawable(null);
        if (backdropBitmap != null) backdropBitmap.recycle();
        backdropBitmap = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------ layout

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B1020);

        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0f);
        root.addView(backdrop, match());

        // Darker on the left where the list sits, lets the photo glow through on the right.
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xE6080B14, 0xB3080B14, 0x40080B14}));
        root.addView(shade, match());

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        int padX = dp(56), padY = dp(26);
        scroll.setPadding(padX, padY, padX, padY);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                Math.round(getResources().getDisplayMetrics().widthPixels * 0.58f),
                FrameLayout.LayoutParams.MATCH_PARENT);
        root.addView(scroll, slp);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);

        list.addView(text("Immich Screensaver", 28, Color.WHITE, 0, 0));
        subtitle = text("", 15, 0xFFB8C1D9, 0, dp(10));
        list.addView(subtitle);

        previewRow = row(list, "Preview screensaver", v -> openPreview());
        ((TextView) ((LinearLayout) previewRow).getChildAt(1)).setText("▶");
        albumsValue = value(albumsRow = row(list, "Albums", v -> openAlbums()));
        serverValue = value(serverRow = row(list, "Server",
                v -> startActivity(new Intent(this, SetupActivity.class))));
        orderValue = value(row(list, "Order", v -> { prefs.order = (prefs.order + 1) % 3; save(); }));
        durationValue = value(row(list, "Photo duration", v -> { prefs.nextDuration(); save(); }));
        styleValue = value(row(list, "Photo style", v -> { prefs.style = (prefs.style + 1) % 3; save(); }));
        zoomValue = value(row(list, "Slow zoom", v -> { prefs.zoom = !prefs.zoom; save(); }));
        clockValue = value(row(list, "Clock", v -> { prefs.clock = (prefs.clock + 1) % 3; save(); }));
        infoValue = value(row(list, "Photo date & place", v -> { prefs.info = !prefs.info; save(); }));
        dimValue = value(row(list, "Dim screen", v -> { prefs.dim = !prefs.dim; save(); }));
        homeValue = value(row(list, "Home screen background", v -> { prefs.homePhotos = !prefs.homePhotos; save(); }));

        footer = text("", 13, 0xFF9AA3B8, dp(10), 0);
        footer.setLineSpacing(0f, 1.2f);
        list.addView(footer);
        return root;
    }

    private View row(LinearLayout parent, String title, View.OnClickListener onClick) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setClickable(true);
        r.setPadding(dp(18), dp(8), dp(18), dp(8));
        r.setBackground(rowBackground());
        r.setOnClickListener(onClick);

        TextView t = text(title, 18, Color.WHITE, 0, 0);
        r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text("", 17, 0xFFA9C1FF, 0, 0);
        v.setGravity(Gravity.END);
        r.addView(v);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        parent.addView(r, lp);
        return r;
    }

    private static TextView value(View row) {
        return (TextView) ((LinearLayout) row).getChildAt(1);
    }

    private StateListDrawable rowBackground() {
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0x33FFFFFF);
        focused.setStroke(dp(1), 0x55FFFFFF);
        focused.setCornerRadius(dp(12));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return s;
    }

    private TextView text(String s, int sp, int color, int top, int bottom) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setPadding(0, top, 0, bottom);
        return tv;
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------ state

    private void save() {
        prefs.save(this);
        refresh();
    }

    private void refresh() {
        Config c = Config.load(this);
        if (c.isComplete()) {
            subtitle.setText("Showing " + (c.albumName == null ? "your album" : "“" + c.albumName + "”")
                    + " from " + c.serverUrl);
            albumsValue.setText(c.albumName != null ? c.albumName
                    : (c.albumIds.size() == 1 ? "1 album" : c.albumIds.size() + " albums"));
            serverValue.setText(c.serverUrl.replaceFirst("^https?://", ""));
        } else {
            subtitle.setText("Not connected yet. Select “Server” to scan a QR code with your phone.");
            albumsValue.setText("");
            serverValue.setText("Set up");
        }
        orderValue.setText(Prefs.orderLabel(prefs.order));
        durationValue.setText(Prefs.durationLabel(prefs.durationSec));
        styleValue.setText(Prefs.styleLabel(prefs.style));
        zoomValue.setText(prefs.zoom ? "On" : "Off");
        clockValue.setText(Prefs.clockLabel(prefs.clock));
        infoValue.setText(prefs.info ? "On" : "Off");
        dimValue.setText(prefs.dim ? "On" : "Off");
        homeValue.setText(prefs.homePhotos ? "Photos" : "Gradient");

        Boolean active = isActiveScreensaver();
        if (Boolean.TRUE.equals(active)) {
            footer.setText("✓ This is the TV's screensaver. Changes apply the next time it starts.");
        } else {
            footer.setText((active == null ? "" : "Not the TV's screensaver yet. ")
                    + "To set it, run once from a computer:\nadb shell settings put secure "
                    + "screensaver_components " + DREAM_COMPONENT);
        }
        footer.append(homeButtonEnabled()
                ? "\n✓ The Home button opens the photo home screen."
                : "\nHome button: enable “Immich Home button” in Settings › Accessibility, or see README.");

        if (getCurrentFocus() == null) {
            (c.isComplete() ? previewRow : (c.serverUrl != null ? albumsRow : serverRow)).requestFocus();
        }
    }

    private boolean homeButtonEnabled() {
        try {
            String v = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return v != null && v.contains(getPackageName() + "/" + HomeRedirectService.class.getName());
        } catch (Exception e) {
            return false;
        }
    }

    /** TRUE/FALSE if readable, null if the system does not let us read the setting. */
    private Boolean isActiveScreensaver() {
        try {
            String v = Settings.Secure.getString(getContentResolver(), "screensaver_components");
            if (v == null) return null;
            return v.contains(DREAM_COMPONENT) || v.contains(DREAM_COMPONENT_LONG);
        } catch (Exception e) {
            return null;
        }
    }

    private void openAlbums() {
        Config c = Config.load(this);
        if (c.serverUrl == null || c.apiKey == null) {
            startActivity(new Intent(this, SetupActivity.class));
        } else {
            startActivity(new Intent(this, AlbumsActivity.class));
        }
    }

    private void openPreview() {
        if (!Config.load(this).isComplete()) {
            startActivity(new Intent(this, SetupActivity.class));
        } else {
            startActivity(new Intent(this, PreviewActivity.class));
        }
    }

    /** Loads the album cover at thumbnail size and blurs it into a tiny backdrop. */
    private void loadBackdrop() {
        final Config c = Config.load(this);
        if (!c.isComplete()) return;
        String key = c.serverUrl + "|" + c.albumIds.get(0);
        if (backdropRequested && key.equals(backdropFor)) return; // reload only if the album changed
        backdropRequested = true;
        backdropFor = key;
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
                // Backdrop is decoration only.
            }
            final Bitmap result = blurred;
            runOnUiThread(() -> {
                if (result == null) return;
                if (isFinishing() || isDestroyed()) {
                    result.recycle();
                    return;
                }
                backdrop.setImageBitmap(result);
                if (backdropBitmap != null) backdropBitmap.recycle();
                backdropBitmap = result;
                backdrop.animate().alpha(1f).setDuration(800);
            });
        }, "immich-backdrop").start();
    }
}
