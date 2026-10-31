package com.fehlenfusion.immichscreensaver;

import android.content.Context;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tiny single-threaded HTTP server that serves the phone-side setup page.
 * Runs only while {@link SetupActivity} is in the foreground. Every path is prefixed
 * with a random one-time token that is only shown in the QR code on the TV screen.
 */
final class SetupServer {
    interface Listener {
        /** Called on the server thread after a configuration was saved. */
        void onSaved(Config config);
    }

    private static final String TAG = "ImmichSetup";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    static final int PREFERRED_PORT = 8765;
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_BODY_BYTES = 8 * 1024;

    private final Context appContext;
    private final Listener listener;
    final String token;
    private volatile ServerSocket socket;
    private Thread thread;

    // Pending credentials between step 1 (connect) and step 2 (pick album). Server thread only.
    private String pendingUrl;
    private String pendingKey;
    private List<AssetParser.Album> pendingAlbums;

    SetupServer(Context ctx, Listener listener) {
        this.appContext = ctx.getApplicationContext();
        this.listener = listener;
        byte[] b = new byte[8];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xFF));
        token = sb.toString();
    }

    /** Starts listening; returns the bound port. */
    int start() throws IOException {
        ServerSocket ss;
        try {
            ss = new ServerSocket(PREFERRED_PORT);
        } catch (IOException e) {
            ss = new ServerSocket(0);
        }
        socket = ss;
        final ServerSocket s = ss;
        thread = new Thread(() -> loop(s), "immich-setup");
        thread.start();
        return ss.getLocalPort();
    }

    void stop() {
        ServerSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private void loop(ServerSocket ss) {
        while (socket == ss) {
            Socket client;
            try {
                client = ss.accept();
            } catch (SocketException e) {
                return; // closed
            } catch (IOException e) {
                continue;
            }
            try {
                client.setSoTimeout(15_000);
                handle(client);
            } catch (Throwable t) {
                Log.w(TAG, "request failed: " + t.getClass().getSimpleName());
            } finally {
                try {
                    client.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------ HTTP

    private void handle(Socket client) throws IOException {
        InputStream in = client.getInputStream();
        String head = readHead(in);
        if (head == null) return;
        String[] lines = head.split("\r\n");
        String[] req = lines[0].split(" ");
        if (req.length < 2) return;
        String method = req[0];
        String path = req[1];
        int contentLength = 0;
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0 && lines[i].substring(0, c).trim().equalsIgnoreCase("content-length")) {
                try {
                    contentLength = Integer.parseInt(lines[i].substring(c + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        OutputStream out = new BufferedOutputStream(client.getOutputStream());
        if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
            respond(out, 413, page("Too large", "<p>Request too large.</p>"));
            return;
        }
        String body = contentLength > 0 ? new String(readN(in, contentLength), UTF8) : "";

        String prefix = "/s/" + token;
        if (!path.equals(prefix) && !path.startsWith(prefix + "/")) {
            respond(out, 404, page("Not found",
                    "<p>This link has expired. Scan the QR code on the TV again.</p>"));
            return;
        }
        String sub = path.substring(prefix.length());
        Map<String, String> form = parseForm(body);

        if ("GET".equals(method) && (sub.isEmpty() || sub.equals("/"))) {
            respond(out, 200, connectPage(null));
        } else if ("POST".equals(method) && sub.equals("/connect")) {
            respond(out, 200, handleConnect(form));
        } else if ("POST".equals(method) && sub.equals("/save")) {
            respond(out, 200, handleSave(form));
        } else {
            respond(out, 404, page("Not found", "<p>Not found.</p>"));
        }
    }

    private String handleConnect(Map<String, String> form) {
        Config existing = Config.load(appContext);
        String url = Config.normalizeUrl(form.get("url"));
        String key = form.get("key");
        if (key != null) key = key.trim();
        if ((key == null || key.isEmpty()) && existing.apiKey != null) key = existing.apiKey;
        if (url == null) return connectPage("That server address doesn't look right. Example: http://192.168.1.10:2283");
        if (!Config.isPlausibleKey(key)) return connectPage("Please paste the API key.");

        List<AssetParser.Album> albums;
        try {
            albums = new ImmichClient(url, key).listAlbums();
        } catch (ImmichClient.HttpStatusException e) {
            if (e.code == 401 || e.code == 403) {
                return connectPage("The server rejected the API key (HTTP " + e.code
                        + "). It needs the album.read, asset.read and asset.view permissions.");
            }
            return connectPage("The server answered with HTTP " + e.code + ". Is this the Immich address?");
        } catch (Exception e) {
            return connectPage("Couldn't reach the server from the TV (" + e.getClass().getSimpleName()
                    + "). Check the address and that the TV is on the same network.");
        }
        if (albums.isEmpty()) return connectPage("Connected, but this key can't see any albums.");

        pendingUrl = url;
        pendingKey = key;
        pendingAlbums = albums;

        StringBuilder sb = new StringBuilder();
        sb.append("<p class=ok>Connected to ").append(esc(url)).append(".</p>");
        sb.append("<form method=post action='/s/").append(token).append("/save'>");
        sb.append("<p>Choose the album to show:</p>");
        boolean first = true;
        for (AssetParser.Album a : albums) {
            boolean checked = a.id.equals(existing.albumId) || (first && existing.albumId == null);
            sb.append("<label class=album><input type=radio name=album value='").append(a.id).append("'")
                    .append(checked ? " checked" : "").append("> ")
                    .append(esc(a.name)).append(" <small>(").append(a.assetCount).append(")</small></label>");
            first = false;
        }
        sb.append("<button type=submit>Save</button></form>");
        return page("Choose album", sb.toString());
    }

    private String handleSave(Map<String, String> form) {
        String albumId = form.get("album");
        if (pendingUrl == null || pendingAlbums == null) return connectPage("Please connect first.");
        AssetParser.Album chosen = null;
        for (AssetParser.Album a : pendingAlbums) if (a.id.equals(albumId)) chosen = a;
        if (chosen == null) return connectPage("Please pick an album.");

        Config cfg = new Config(pendingUrl, pendingKey, chosen.id, chosen.name);
        cfg.save(appContext);
        pendingUrl = pendingKey = null;
        pendingAlbums = null;
        listener.onSaved(cfg);
        return page("Done", "<p class=ok>Saved. The TV will show <b>" + esc(chosen.name)
                + "</b> as its screensaver.</p><p>You can close this page.</p>");
    }

    // ------------------------------------------------------------ pages

    private String connectPage(String error) {
        Config c = Config.load(appContext);
        StringBuilder sb = new StringBuilder();
        if (error != null) sb.append("<p class=err>").append(esc(error)).append("</p>");
        sb.append("<form method=post action='/s/").append(token).append("/connect'>")
                .append("<label>Immich server address<input name=url type=url required autocapitalize=off "
                        + "placeholder='http://192.168.1.10:2283' value='")
                .append(c.serverUrl == null ? "" : esc(c.serverUrl)).append("'></label>")
                .append("<label>API key<input name=key type=password autocomplete=off autocapitalize=off "
                        + "placeholder='")
                .append(c.apiKey != null ? "(leave blank to keep the saved key)" : "paste the key").append("'></label>")
                .append("<p class=hint>Create the key in Immich → Account settings → API keys, with "
                        + "<code>album.read</code>, <code>asset.read</code> and <code>asset.view</code> only.</p>")
                .append("<button type=submit>Connect</button></form>");
        return page("Immich Screensaver setup", sb.toString());
    }

    private static String page(String title, String body) {
        return "<!doctype html><html><head><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<meta name=referrer content=no-referrer>"
                + "<title>" + esc(title) + "</title><style>"
                + "body{font:16px/1.45 system-ui,sans-serif;margin:0;padding:20px;background:#111827;color:#f3f4f6}"
                + "main{max-width:480px;margin:auto}h1{font-size:22px}"
                + "label{display:block;margin:14px 0}input[type=url],input[type=password]{display:block;width:100%;"
                + "box-sizing:border-box;margin-top:6px;padding:12px;font-size:16px;border-radius:8px;"
                + "border:1px solid #4b5563;background:#1f2937;color:#f9fafb}"
                + "label.album{padding:12px;border:1px solid #374151;border-radius:8px;margin:8px 0}"
                + "button{width:100%;padding:14px;font-size:17px;border:0;border-radius:8px;background:#4f7cff;"
                + "color:#fff;margin-top:12px}.err{color:#fca5a5}.ok{color:#86efac}.hint,small{color:#9ca3af}"
                + "code{font-size:13px}</style></head><body><main><h1>" + esc(title) + "</h1>"
                + body + "</main></body></html>";
    }

    // ------------------------------------------------------------ helpers

    private static void respond(OutputStream out, int code, String html) throws IOException {
        byte[] body = html.getBytes(UTF8);
        String status = code == 200 ? "OK" : code == 404 ? "Not Found" : "Error";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Frame-Options: DENY\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(UTF8));
        out.write(body);
        out.flush();
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(1024);
        int state = 0, b;
        while ((b = in.read()) >= 0) {
            buf.write(b);
            if (buf.size() > MAX_HEADER_BYTES) return null;
            // Look for \r\n\r\n
            if ((state == 0 || state == 2) && b == '\r') state++;
            else if ((state == 1 || state == 3) && b == '\n') state++;
            else state = b == '\r' ? 1 : 0;
            if (state == 4) return new String(buf.toByteArray(), UTF8);
        }
        return null;
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new IOException("truncated body");
            off += r;
        }
        return b;
    }

    static Map<String, String> parseForm(String body) {
        Map<String, String> m = new HashMap<>();
        if (body == null || body.isEmpty()) return m;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                m.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            } catch (UnsupportedEncodingException | IllegalArgumentException ignored) {
            }
        }
        return m;
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
