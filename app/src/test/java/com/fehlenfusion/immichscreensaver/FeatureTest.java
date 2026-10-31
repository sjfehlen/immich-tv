package com.fehlenfusion.immichscreensaver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

public class FeatureTest {
    private static final String A = "11111111-1111-4111-8111-111111111111";
    private static final String B = "22222222-2222-4222-8222-222222222222";

    @Test
    public void boxBlurKeepsUniformImageAndSpreadsAPoint() {
        int w = 9, h = 7;
        int[] flat = new int[w * h];
        Arrays.fill(flat, 0xFF336699);
        Blur.boxBlur(flat, w, h, 2);
        for (int p : flat) assertEquals(0xFF336699, p);

        int[] dot = new int[w * h];
        Arrays.fill(dot, 0xFF000000);
        dot[3 * w + 4] = 0xFFFFFFFF;
        Blur.boxBlur(dot, w, h, 1);
        int center = dot[3 * w + 4] & 0xFF;
        int neighbour = dot[3 * w + 5] & 0xFF;
        int far = dot[0] & 0xFF;
        assertTrue(center > 0 && neighbour > 0 && far == 0);
        for (int p : dot) assertEquals(0xFF, p >>> 24); // stays opaque
    }

    @Test
    public void photoInfoUsesLocalDateAndPlace() throws Exception {
        String us = "{\"localDateTime\":\"2023-10-12T14:33:00.000Z\",\"exifInfo\":"
                + "{\"city\":\"Austin\",\"state\":\"Texas\",\"country\":\"United States of America\"}}";
        assertEquals("October 12, 2023 · Austin, Texas", AssetParser.photoInfo(us, Locale.US));

        String abroad = "{\"localDateTime\":\"2019-01-01T23:59:00.000Z\",\"exifInfo\":"
                + "{\"city\":\"Kyoto\",\"state\":\"Kyoto\",\"country\":\"Japan\"}}";
        assertEquals("January 1, 2019 · Kyoto, Japan", AssetParser.photoInfo(abroad, Locale.US));

        String dateOnly = "{\"localDateTime\":\"2020-02-29T00:00:00.000Z\",\"exifInfo\":{\"city\":null}}";
        assertEquals("February 29, 2020", AssetParser.photoInfo(dateOnly, Locale.US));

        assertNull(AssetParser.photoInfo("{\"exifInfo\":{}}", Locale.US));
    }

    @Test
    public void albumIdListsRoundTrip() {
        assertEquals(Arrays.asList(A, B), Config.parseIds(A + ", " + B + ",junk," + A));
        assertEquals(Collections.singletonList(A), Config.parseIds(A)); // old single-album prefs
        assertEquals(A + "," + B, Config.joinIds(Arrays.asList(A, B)));
        assertEquals(0, Config.parseIds(null).size());
        assertEquals("Family", Config.summarize(Collections.singletonList("Family")));
        assertEquals("Family & Trips", Config.summarize(Arrays.asList("Family", "Trips")));
        assertEquals("Family + 2 more", Config.summarize(Arrays.asList("Family", "Trips", "Pets")));
    }

    @Test
    public void checkboxValues() {
        assertEquals(Arrays.asList(A, B), SetupServer.formValues("album=" + A + "&x=1&album=" + B, "album"));
        assertEquals(0, SetupServer.formValues("", "album").size());
    }

    @Test
    public void durationCyclesAndLabels() {
        Prefs p = new Prefs();
        assertEquals(20, p.durationSec);
        p.nextDuration();
        assertEquals(30, p.durationSec);
        p.durationSec = 300;
        p.nextDuration();
        assertEquals(10, p.durationSec);
        assertEquals("1 minute", Prefs.durationLabel(60));
        assertEquals("5 minutes", Prefs.durationLabel(300));
        assertEquals("20 seconds", Prefs.durationLabel(20));
    }

    @Test
    public void orderSortsByTakenDateAndSequentialPlaylistLoops() throws Exception {
        String C = "33333333-3333-4333-8333-333333333333";
        java.util.List<AssetParser.Photo> photos = AssetParser.photos(new org.json.JSONArray("["
                + "{\"id\":\"" + A + "\",\"type\":\"IMAGE\",\"localDateTime\":\"2021-05-01T10:00:00.000Z\"},"
                + "{\"id\":\"" + B + "\",\"type\":\"IMAGE\",\"localDateTime\":\"2024-01-01T10:00:00.000Z\"},"
                + "{\"id\":\"" + C + "\",\"type\":\"IMAGE\",\"fileCreatedAt\":\"2019-12-31T10:00:00.000Z\"}]"));
        assertEquals(Arrays.asList(B, A, C), Playlist.ordered(photos, Prefs.ORDER_NEWEST));
        assertEquals(Arrays.asList(C, A, B), Playlist.ordered(photos, Prefs.ORDER_OLDEST));
        assertEquals(Arrays.asList(A, B, C), Playlist.ordered(photos, Prefs.ORDER_SHUFFLE));

        Playlist p = new Playlist(Arrays.asList(B, A, C), new java.util.Random(), false);
        assertEquals(Arrays.asList(B, A, C, B, A), Arrays.asList(p.next(), p.next(), p.next(), p.next(), p.next()));
        assertEquals("Shuffle", Prefs.orderLabel(Prefs.ORDER_SHUFFLE));
    }

    @Test
    public void favoritesMoveWithinBounds() {
        java.util.List<String> l = Arrays.asList("a", "b", "c");
        assertEquals(Arrays.asList("b", "a", "c"), AppsRepo.move(l, "b", -1));
        assertEquals(Arrays.asList("a", "c", "b"), AppsRepo.move(l, "b", 1));
        assertEquals(Arrays.asList("a", "b", "c"), AppsRepo.move(l, "a", -1)); // already first
        assertEquals(Arrays.asList("a", "b", "c"), AppsRepo.move(l, "c", 1));  // already last
        assertEquals(Arrays.asList("a", "b", "c"), AppsRepo.move(l, "zzz", 1));
    }
}
