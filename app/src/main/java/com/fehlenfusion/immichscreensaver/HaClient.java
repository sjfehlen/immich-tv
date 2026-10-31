package com.fehlenfusion.immichscreensaver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/** Minimal Home Assistant REST client: check the connection and run a script. Blocking; call off the main thread. */
final class HaClient {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final String baseUrl;
    private final String token;

    HaClient(String baseUrl, String token) {
        this.baseUrl = baseUrl;
        this.token = token;
    }

    static HaClient of(HaConfig c) {
        return new HaClient(c.url, c.token);
    }

    /** GET /api/ answers 200 only with a valid token. */
    void check() throws IOException {
        request("GET", "/api/", null);
    }

    /** Starts a script without waiting for it to finish. */
    void runScript(String entityId) throws IOException {
        request("POST", "/api/services/script/turn_on", "{\"entity_id\":\"" + entityId + "\"}");
    }

    private void request(String method, String path, String jsonBody) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        try {
            c.setConnectTimeout(8_000);
            c.setReadTimeout(15_000);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setRequestMethod(method);
            c.setRequestProperty("Authorization", "Bearer " + token);
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
                byte[] buf = new byte[4096];
                int total = 0, n;
                while ((n = in.read(buf)) >= 0) {
                    total += n;
                    if (total > 256 * 1024) break; // we don't need the body
                }
            }
        } finally {
            c.disconnect();
        }
    }
}
