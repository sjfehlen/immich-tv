package com.fehlenfusion.immichscreensaver;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.List;

/**
 * Minimal Jellyfin client: public server info, Quick Connect sign-in, and read-only
 * "continue watching" / "next up" lists with their images. Never logs URLs or tokens.
 */
final class JellyfinClient {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_JSON = 4 * 1024 * 1024;
    private static final int MAX_IMAGE = 4 * 1024 * 1024;
    static final int CARD_LIMIT = 12;

    private final String baseUrl;
    private final String deviceId;
    private final String token;

    JellyfinClient(String baseUrl, String deviceId, String token) {
        this.baseUrl = baseUrl;
        this.deviceId = deviceId;
        this.token = token;
    }

    static JellyfinClient of(JellyfinConfig c) {
        return new JellyfinClient(c.serverUrl, c.deviceId, c.token);
    }

    /** GET /System/Info/Public: returns the server name, or throws if it isn't Jellyfin. */
    String serverName() throws IOException, JSONException {
        JSONObject o = new JSONObject(get("/System/Info/Public"));
        if (AssetParser.str(o, "Id") == null) throw new IOException("not a Jellyfin server");
        String n = AssetParser.str(o, "ServerName");
        return n == null ? "Jellyfin" : n;
    }

    JellyfinParser.QuickConnect quickConnectInitiate() throws IOException, JSONException {
        try {
            return JellyfinParser.quickConnect(text(request("POST", "/QuickConnect/Initiate", "", MAX_JSON)));
        } catch (ImmichClient.HttpStatusException e) {
            if (e.code == 404 || e.code == 405) { // Jellyfin 10.8 used GET
                return JellyfinParser.quickConnect(get("/QuickConnect/Initiate"));
            }
            throw e;
        }
    }

    JellyfinParser.QuickConnect quickConnectState(String secret) throws IOException, JSONException {
        return JellyfinParser.quickConnect(get("/QuickConnect/Connect?secret=" + enc(secret)));
    }

    /** Returns {token, userId, userName}. */
    String[] quickConnectSignIn(String secret) throws IOException, JSONException {
        String body = new JSONObject().put("Secret", secret).toString();
        String[] r = JellyfinParser.signIn(text(request("POST", "/Users/AuthenticateWithQuickConnect", body, MAX_JSON)));
        if (r == null) throw new IOException("sign-in failed");
        return r;
    }

    /** In-progress items, then next-up episodes. */
    List<JellyfinParser.Card> continueWatching(String userId) throws IOException, JSONException {
        String common = "&limit=" + CARD_LIMIT + "&enableUserData=true&enableTotalRecordCount=false"
                + "&enableImageTypes=Primary,Backdrop,Thumb&imageTypeLimit=1";
        String resume;
        try {
            resume = get("/UserItems/Resume?userId=" + enc(userId) + "&mediaTypes=Video" + common);
        } catch (ImmichClient.HttpStatusException e) {
            if (e.code != 404) throw e; // servers before 10.9
            resume = get("/Users/" + enc(userId) + "/Items/Resume?mediaTypes=Video" + common);
        }
        List<JellyfinParser.Card> nextUp;
        try {
            nextUp = JellyfinParser.cards(get("/Shows/NextUp?userId=" + enc(userId)
                    + "&enableResumable=false&enableRewatching=false" + common));
        } catch (IOException | JSONException e) {
            nextUp = new java.util.ArrayList<>(); // next up is a bonus; resume still shows
        }
        return JellyfinParser.merge(JellyfinParser.cards(resume), nextUp, CARD_LIMIT);
    }

    /** Card art, decoded small (RGB_565) for a card of maxW px. */
    Bitmap image(JellyfinParser.Card c, int maxW) throws IOException {
        if (c.imageItemId == null) return null;
        byte[] data = request("GET", "/Items/" + c.imageItemId + "/Images/" + c.imagePath
                + "?maxWidth=" + maxW + "&quality=85&tag=" + enc(c.imageTag), null, MAX_IMAGE);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    // ------------------------------------------------------------ HTTP

    private String get(String path) throws IOException {
        return text(request("GET", path, null, MAX_JSON));
    }

    private static String text(byte[] b) {
        return new String(b, UTF8);
    }

    private String authHeader() {
        String h = "MediaBrowser Client=\"Immich Home\", Device=\"Android TV\", DeviceId=\"" + deviceId
                + "\", Version=\"1.0\"";
        return token != null ? h + ", Token=\"" + token + "\"" : h;
    }

    private byte[] request(String method, String path, String jsonBody, int maxBytes) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(20_000);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setRequestMethod(method);
            c.setRequestProperty("Authorization", authHeader());
            if (jsonBody != null) {
                byte[] body = jsonBody.getBytes(UTF8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body);
                }
            }
            int code = c.getResponseCode();
            if (code < 200 || code > 299) throw new ImmichClient.HttpStatusException(code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    if (out.size() > maxBytes) throw new IOException("response too large");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s, "UTF-8");
    }
}
