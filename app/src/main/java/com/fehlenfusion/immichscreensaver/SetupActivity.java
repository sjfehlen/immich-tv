package com.fehlenfusion.immichscreensaver;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * TV setup screen. Shows a QR code linking to the phone setup page served by
 * {@link SetupServer}. The server only runs while this screen is visible.
 */
public final class SetupActivity extends Activity implements SetupServer.Listener {
    private SetupServer server;
    private ImageView qrView;
    private TextView urlView;
    private TextView statusView;
    private Bitmap qrBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildLayout());
    }

    @Override
    protected void onStart() {
        super.onStart();
        refreshStatus();
        String ip = lanAddress();
        if (ip == null) {
            showQr(null, "This TV isn't connected to a network.");
            return;
        }
        server = new SetupServer(this, this);
        try {
            int port = server.start();
            String host = ip.contains(":") ? "[" + ip + "]" : ip;
            String url = "http://" + host + ":" + port + "/s/" + server.token;
            showQr(url, url);
        } catch (Exception e) {
            server = null;
            showQr(null, "Couldn't start the setup page (" + e.getClass().getSimpleName() + ").");
        }
    }

    @Override
    protected void onStop() {
        if (server != null) server.stop();
        server = null;
        showQr(null, "");
        super.onStop();
    }

    @Override
    public void onSaved(Config config) {
        runOnUiThread(() -> {
            refreshStatus();
            statusView.setText("\u2713 Saved: " + config.albumName + ". Returning to settings\u2026");
            statusView.postDelayed(this::finish, 2500);
        });
    }

    // ------------------------------------------------------------ UI

    private View buildLayout() {
        int pad = dp(48);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setBackgroundColor(0xFF111827);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        root.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        text.addView(label("Immich Screensaver", 34, Color.WHITE));
        text.addView(label("1.  Scan the QR code with your phone (same Wi-Fi as the TV).\n"
                + "2.  Enter your Immich server address and API key.\n"
                + "3.  Pick the album to show.", 18, 0xFFD1D5DB));
        urlView = label("", 14, 0xFF9CA3AF);
        text.addView(urlView);
        statusView = label("", 18, 0xFF86EFAC);
        text.addView(statusView);
        text.addView(label("Press Back to return to settings.", 14, 0xFF9CA3AF));

        qrView = new ImageView(this);
        qrView.setBackgroundColor(Color.WHITE);
        int qrSize = dp(320);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(qrSize, qrSize);
        qlp.leftMargin = dp(48);
        root.addView(qrView, qlp);
        return root;
    }

    private TextView label(String s, int sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setLineSpacing(0f, 1.25f);
        tv.setPadding(0, dp(8), 0, dp(8));
        return tv;
    }

    private void refreshStatus() {
        Config c = Config.load(this);
        if (c.isComplete()) {
            statusView.setTextColor(0xFF86EFAC);
            statusView.setText("Connected: \u201C" + (c.albumName == null ? "album" : c.albumName)
                    + "\u201D on " + c.serverUrl + ". Scan again to change.");
        } else {
            statusView.setTextColor(0xFFFCD34D);
            statusView.setText("Not set up yet.");
        }
    }

    private void showQr(String url, String caption) {
        qrView.setImageDrawable(null);
        if (qrBitmap != null) qrBitmap.recycle();
        qrBitmap = null;
        urlView.setText(caption);
        if (url == null) {
            qrView.setVisibility(View.INVISIBLE);
            return;
        }
        qrBitmap = render(QrCode.encode(url.getBytes(java.nio.charset.Charset.forName("UTF-8"))));
        BitmapDrawable d = new BitmapDrawable(getResources(), qrBitmap);
        d.setFilterBitmap(false); // keep modules crisp when scaled
        qrView.setImageDrawable(d);
        qrView.setVisibility(View.VISIBLE);
    }

    /** One pixel per module plus a 4-module quiet zone; the ImageView scales it up. */
    private static Bitmap render(QrCode qr) {
        int quiet = 4;
        int n = qr.size + quiet * 2;
        int[] px = new int[n * n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int qx = x - quiet, qy = y - quiet;
                boolean dark = qx >= 0 && qy >= 0 && qx < qr.size && qy < qr.size && qr.get(qx, qy);
                px[y * n + x] = dark ? Color.BLACK : Color.WHITE;
            }
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** First site-local IPv4 address on an up, non-loopback interface (Ethernet or Wi-Fi). */
    private static String lanAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
