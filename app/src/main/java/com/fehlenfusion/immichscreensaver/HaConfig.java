package com.fehlenfusion.immichscreensaver;

import android.content.Context;
import android.content.SharedPreferences;

/** Home Assistant connection: base URL plus a long-lived access token. App-private storage only. */
final class HaConfig {
    private static final String PREFS = "homeassistant";

    final String url;
    final String token;

    private HaConfig(String url, String token) {
        this.url = url;
        this.token = token;
    }

    boolean isConnected() {
        return url != null && token != null;
    }

    static HaConfig load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new HaConfig(p.getString("url", null), p.getString("token", null));
    }

    static void save(Context ctx, String url, String token) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("url", url).putString("token", token).commit();
    }

    /**
     * Accepts what people paste from a browser ("http://ha.local:8123/lovelace/0"): keeps only
     * scheme, host and port, since Home Assistant is always served from the root.
     */
    static String normalizeUrl(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)^(https?://[A-Za-z0-9.\\-\\[\\]:]+?(:[0-9]{1,5})?)(?:[/?#].*)?$").matcher(s);
        return m.matches() ? m.group(1) : null;
    }

    /** Long-lived tokens are JWTs: three base64url parts. */
    static boolean isPlausibleToken(String t) {
        return t != null && t.length() >= 40 && t.length() <= 2048
                && t.matches("[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+");
    }
}
