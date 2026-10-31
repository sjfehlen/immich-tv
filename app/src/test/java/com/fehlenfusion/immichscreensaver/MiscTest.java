package com.fehlenfusion.immichscreensaver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class MiscTest {

    @Test
    public void playlistShowsEveryIdOncePerRoundAndNeverBackToBack() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 7; i++) ids.add("id" + i);
        Playlist p = new Playlist(ids, new Random(1));
        String prev = null;
        for (int round = 0; round < 50; round++) {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < ids.size(); i++) {
                String id = p.next();
                assertTrue("repeat within round", seen.add(id));
                assertNotEquals("same photo twice in a row", prev, id);
                prev = id;
            }
            assertEquals(new HashSet<>(ids), seen);
        }
    }

    @Test
    public void playlistEdgeCases() {
        assertNull(new Playlist(new ArrayList<>(), new Random()).next());
        List<String> one = new ArrayList<>();
        one.add("a");
        Playlist p = new Playlist(one, new Random());
        assertEquals("a", p.next());
        assertEquals("a", p.next());
    }

    @Test
    public void sampleSizeKeepsBitmapWithin1080p() {
        assertEquals(1, ImmichClient.sampleSize(1440, 1080, 1920, 1080));
        assertEquals(1, ImmichClient.sampleSize(1920, 1080, 1920, 1080));
        assertEquals(2, ImmichClient.sampleSize(1080, 1440, 1920, 1080)); // portrait preview
        assertEquals(2, ImmichClient.sampleSize(3840, 2160, 1920, 1080));
        assertEquals(4, ImmichClient.sampleSize(4000, 3000, 1920, 1080));
        int[][] dims = {{6000, 4000}, {1081, 100}, {100, 1921}, {7, 9}, {12000, 9000}};
        for (int[] d : dims) {
            int s = ImmichClient.sampleSize(d[0], d[1], 1920, 1080);
            assertTrue(d[0] / s <= 1920 && d[1] / s <= 1080);
        }
    }

    @Test
    public void normalizesServerUrls() {
        assertEquals("http://10.0.0.5:2283", Config.normalizeUrl(" http://10.0.0.5:2283/ "));
        assertEquals("http://10.0.0.5:2283", Config.normalizeUrl("http://10.0.0.5:2283/api"));
        assertEquals("https://photos.example.com", Config.normalizeUrl("https://photos.example.com/api/"));
        assertEquals("https://example.com/immich", Config.normalizeUrl("https://example.com/immich"));
        assertNull(Config.normalizeUrl("10.0.0.5:2283"));
        assertNull(Config.normalizeUrl("ftp://10.0.0.5"));
        assertNull(Config.normalizeUrl("http://evil.com/?x=<script>"));
        assertNull(Config.normalizeUrl(""));
    }

    @Test
    public void apiKeyValidation() {
        assertTrue(Config.isPlausibleKey("AbCdEf0123456789xyzXYZ_-"));
        assertFalse(Config.isPlausibleKey("short"));
        assertFalse(Config.isPlausibleKey("has space in it 12345678"));
        assertFalse(Config.isPlausibleKey(null));
    }

    @Test
    public void parsesFormAndEscapesHtml() {
        Map<String, String> m = SetupServer.parseForm("url=http%3A%2F%2F10.0.0.5%3A2283&key=a+b&x");
        assertEquals("http://10.0.0.5:2283", m.get("url"));
        assertEquals("a b", m.get("key"));
        assertEquals("", m.get("x"));
        assertEquals("&lt;b&gt;&amp;&quot;&#39;", SetupServer.esc("<b>&\"'"));
    }
}
