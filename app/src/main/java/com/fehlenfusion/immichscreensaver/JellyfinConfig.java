package com.fehlenfusion.immichscreensaver;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/** Jellyfin connection: server URL plus a Quick Connect access token. App-private storage only. */
final class JellyfinConfig {
    private static final String PREFS = "jellyfin";

    final String serverUrl;
    final String token;
    final String userId;
    final String userName;
    final String deviceId;

    private JellyfinConfig(String serverUrl, String token, String userId, String userName, String deviceId) {
        this.serverUrl = serverUrl;
        this.token = token;
        this.userId = userId;
        this.userName = userName;
        this.deviceId = deviceId;
    }

    boolean isConnected() {
        return serverUrl != null && token != null && userId != null;
    }

    static JellyfinConfig load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String device = p.getString("device", null);
        if (device == null) {
            device = UUID.randomUUID().toString().replace("-", "");
            p.edit().putString("device", device).apply();
        }
        return new JellyfinConfig(p.getString("url", null), p.getString("token", null),
                p.getString("user", null), p.getString("userName", null), device);
    }

    /** New server: forget any previous sign-in. */
    static void saveServer(Context ctx, String url) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("url", url).remove("token").remove("user").remove("userName").commit();
    }

    static void saveSignIn(Context ctx, String token, String userId, String userName) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("token", token).putString("user", userId).putString("userName", userName).commit();
    }

    static void disconnect(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("url").remove("token").remove("user").remove("userName").commit();
    }

    /** Accepts what people paste from a browser: strips "/web/..." and trailing slashes. */
    static String normalizeUrl(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        int web = s.indexOf("/web");
        if (web > 0 && s.indexOf("://") < web) s = s.substring(0, web);
        int hash = s.indexOf('#');
        if (hash > 0) s = s.substring(0, hash);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (!s.matches("(?i)^https?://[A-Za-z0-9.\\-\\[\\]:]+(:[0-9]{1,5})?(/[A-Za-z0-9._~\\-/]*)?$")) return null;
        return s;
    }
}
