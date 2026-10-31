package com.fehlenfusion.immichscreensaver;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.nio.charset.Charset;

/**
 * Connect Jellyfin in two steps:
 * 1. a QR code opens a LAN setup page on the phone to enter the server address;
 * 2. the TV shows a Quick Connect code to approve in the Jellyfin app (no password typed).
 * Only the resulting access token is stored.
 */
public final class JellyfinSetupActivity extends Activity implements SetupServer.Listener {
    private static final long POLL_MS = 3_000;
    private static final long QC_TIMEOUT_MS = 10 * 60_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView title, body, status, bigCode;
    private ImageView qrView;
    private Bitmap qrBitmap;
    private LinearLayout buttons;
    private SetupServer server;
    private volatile boolean running;
    private int attempt; // bumps to cancel an older Quick Connect poll

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        int pad = Tiles.dp(this, 48);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0B1020, 0xFF1A1630}));
        root.setPadding(pad, pad, pad, pad);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        root.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        title = text("Connect Jellyfin", 32, Color.WHITE);
        body = text("", 18, 0xFFD1D5DB);
        body.setLineSpacing(0, 1.25f);
        status = text("", 16, 0xFF9CA3AF);
        buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        left.addView(title);
        left.addView(body);
        left.addView(status);
        left.addView(buttons);

        FrameLayout right = new FrameLayout(this);
        int box = Tiles.dp(this, 320);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(box, box);
        rl.leftMargin = pad;
        root.addView(right, rl);
        qrView = new ImageView(this);
        qrView.setBackgroundColor(Color.WHITE);
        right.addView(qrView, new FrameLayout.LayoutParams(-1, -1));
        bigCode = text("", 64, Color.WHITE);
        bigCode.setGravity(Gravity.CENTER);
        bigCode.setLetterSpacing(0.15f);
        GradientDrawable codeBg = new GradientDrawable();
        codeBg.setColor(0x26FFFFFF);
        codeBg.setCornerRadius(Tiles.dp(this, 20));
        bigCode.setBackground(codeBg);
        right.addView(bigCode, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        running = true;
        JellyfinConfig c = JellyfinConfig.load(this);
        if (c.isConnected()) showConnected(c);
        else if (c.serverUrl != null) startQuickConnect(c.serverUrl);
        else startAddressStep();
    }

    @Override
    protected void onStop() {
        running = false;
        attempt++;
        handler.removeCallbacksAndMessages(null);
        stopServer();
        super.onStop();
    }

    // ------------------------------------------------------------ step 1: address via phone

    private void startAddressStep() {
        attempt++;
        buttons.removeAllViews();
        bigCode.setVisibility(View.GONE);
        body.setText("1.  Scan the QR code with your phone (same Wi-Fi as the TV).\n"
                + "2.  Enter your Jellyfin server address.\n"
                + "3.  Approve the code that appears here in the Jellyfin app.");
        String ip = SetupActivity.lanAddress();
        if (ip == null) {
            status.setText("This TV isn't connected to a network.");
            showQr(null);
            return;
        }
        stopServer();
        server = new SetupServer(this, this, true);
        try {
            int port = server.start();
            String url = "http://" + ip + ":" + port + "/s/" + server.token;
            status.setText(url);
            showQr(url);
        } catch (Exception e) {
            server = null;
            status.setText("Couldn't start the setup page (" + e.getClass().getSimpleName() + ").");
        }
    }

    @Override
    public void onSaved(Config config) {
        // Not used in Jellyfin mode.
    }

    @Override
    public void onJellyfinServer(String url, String serverName) {
        JellyfinConfig.saveServer(this, url);
        runOnUiThread(() -> {
            if (!running) return;
            stopServer();
            startQuickConnect(url);
        });
    }

    // ------------------------------------------------------------ step 2: Quick Connect

    private void startQuickConnect(final String url) {
        final int my = ++attempt;
        buttons.removeAllViews();
        showQr(null);
        bigCode.setVisibility(View.VISIBLE);
        bigCode.setText("…");
        body.setText("In the Jellyfin app or web, open Settings › Quick Connect and enter this code.\n"
                + "The code is for this TV only and expires after a few minutes.");
        status.setText(url);
        addButton("Change server", v -> {
            JellyfinConfig.disconnect(this);
            startAddressStep();
        });
        final String device = JellyfinConfig.load(this).deviceId;
        new Thread(() -> {
            JellyfinClient client = new JellyfinClient(url, device, null);
            try {
                final JellyfinParser.QuickConnect qc = client.quickConnectInitiate();
                post(my, () -> bigCode.setText(qc.code));
                long deadline = System.currentTimeMillis() + QC_TIMEOUT_MS;
                while (running && my == attempt && System.currentTimeMillis() < deadline) {
                    Thread.sleep(POLL_MS);
                    if (client.quickConnectState(qc.secret).authenticated) {
                        final String[] r = client.quickConnectSignIn(qc.secret);
                        JellyfinConfig.saveSignIn(this, r[0], r[1], r[2]);
                        post(my, () -> {
                            showConnected(JellyfinConfig.load(this));
                            status.setText("✓ Connected. Returning…");
                            handler.postDelayed(this::finish, 2500);
                        });
                        return;
                    }
                }
                post(my, () -> {
                    bigCode.setText("");
                    status.setText("The code expired.");
                    addButton("Get a new code", v -> startQuickConnect(url));
                });
            } catch (ImmichClient.HttpStatusException e) {
                final String msg = e.code == 401 || e.code == 403 || e.code == 400
                        ? "Quick Connect is turned off on this server. Turn it on in the Jellyfin "
                        + "Dashboard › General › Quick Connect, then try again."
                        : "Jellyfin answered with HTTP " + e.code + ".";
                post(my, () -> failed(url, msg));
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                post(my, () -> failed(url, "Couldn't reach Jellyfin (" + e.getClass().getSimpleName() + ")."));
            }
        }, "jellyfin-qc").start();
    }

    private void failed(String url, String msg) {
        bigCode.setText("");
        status.setText(msg);
        addButton("Try again", v -> startQuickConnect(url)).requestFocus();
    }

    // ------------------------------------------------------------ connected

    private void showConnected(JellyfinConfig c) {
        attempt++;
        showQr(null);
        bigCode.setVisibility(View.VISIBLE);
        bigCode.setText("✓");
        buttons.removeAllViews();
        body.setText("Connected as " + (c.userName == null || c.userName.isEmpty() ? "your user" : c.userName)
                + ".\nOn the home screen, press Down from the apps to see Continue watching.");
        status.setText(c.serverUrl);
        addButton("Reconnect", v -> startQuickConnect(c.serverUrl)).requestFocus();
        addButton("Disconnect", v -> {
            JellyfinConfig.disconnect(this);
            startAddressStep();
        });
    }

    // ------------------------------------------------------------ helpers

    private void post(int my, Runnable r) {
        runOnUiThread(() -> {
            if (running && my == attempt) r.run();
        });
    }

    private void stopServer() {
        if (server != null) server.stop();
        server = null;
    }

    private void showQr(String url) {
        qrView.setImageDrawable(null);
        if (qrBitmap != null) qrBitmap.recycle();
        qrBitmap = null;
        if (url == null) {
            qrView.setVisibility(View.GONE);
            return;
        }
        bigCode.setVisibility(View.GONE);
        qrBitmap = SetupActivity.render(QrCode.encode(url.getBytes(Charset.forName("UTF-8"))));
        BitmapDrawable d = new BitmapDrawable(getResources(), qrBitmap);
        d.setFilterBitmap(false);
        qrView.setImageDrawable(d);
        qrView.setVisibility(View.VISIBLE);
    }

    private View addButton(String label, View.OnClickListener l) {
        TextView b = text(label, 18, Color.WHITE);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(Tiles.dp(this, 22), Tiles.dp(this, 10), Tiles.dp(this, 22), Tiles.dp(this, 10));
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0x40FFFFFF);
        focused.setCornerRadius(Tiles.dp(this, 22));
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(0x14FFFFFF);
        normal.setCornerRadius(Tiles.dp(this, 22));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{}, normal);
        b.setBackground(s);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Tiles.dp(this, 18);
        lp.rightMargin = Tiles.dp(this, 14);
        buttons.addView(b, lp);
        if (buttons.getChildCount() == 1) b.requestFocus();
        return b;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setPadding(0, Tiles.dp(this, 6), 0, Tiles.dp(this, 6));
        return t;
    }

    @Override
    protected void onDestroy() {
        qrView.setImageDrawable(null);
        if (qrBitmap != null) qrBitmap.recycle();
        super.onDestroy();
    }
}
