package com.fehlenfusion.immichscreensaver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class JellyfinTest {
    private static final String EP = "11111111111111111111111111111111";
    private static final String SERIES = "22222222222222222222222222222222";
    private static final String MOVIE = "33333333333333333333333333333333";
    private static final String NEXT = "44444444444444444444444444444444";
    private static final String OTHER_SERIES = "55555555555555555555555555555555";

    private static final String RESUME = "{\"Items\":["
            + "{\"Id\":\"" + EP + "\",\"Type\":\"Episode\",\"Name\":\"Pilot\",\"SeriesName\":\"Severance\","
            + "\"SeriesId\":\"" + SERIES + "\",\"ParentIndexNumber\":1,\"IndexNumber\":1,"
            + "\"RunTimeTicks\":36000000000,\"UserData\":{\"PlayedPercentage\":25.0,\"PlaybackPositionTicks\":9000000000},"
            + "\"ParentThumbItemId\":\"" + SERIES + "\",\"ParentThumbImageTag\":\"abc123\","
            + "\"ImageTags\":{\"Primary\":\"ep1\"}},"
            + "{\"Id\":\"" + MOVIE + "\",\"Type\":\"Movie\",\"Name\":\"Dune\",\"RunTimeTicks\":90000000000,"
            + "\"UserData\":{\"PlayedPercentage\":50.0,\"PlaybackPositionTicks\":45000000000},"
            + "\"ImageTags\":{\"Primary\":\"p\"},\"BackdropImageTags\":[\"bd1\"]},"
            + "{\"Id\":\"not-an-id\",\"Type\":\"Movie\"}"
            + "]}";

    @Test
    public void parsesResumeCards() throws Exception {
        List<JellyfinParser.Card> cards = JellyfinParser.cards(RESUME);
        assertEquals(2, cards.size());

        JellyfinParser.Card ep = cards.get(0);
        assertEquals("Severance", ep.title);
        assertEquals("S1:E1 · Pilot · 45 min left", ep.subtitle);
        assertEquals(0.25f, ep.progress, 0.001f);
        assertEquals(SERIES, ep.imageItemId);
        assertEquals("Thumb", ep.imagePath);   // series thumb preferred for episodes
        assertEquals(SERIES, ep.seriesId);

        JellyfinParser.Card movie = cards.get(1);
        assertEquals("Dune", movie.title);
        assertEquals("75 min left", movie.subtitle);
        assertEquals("Backdrop/0", movie.imagePath); // landscape backdrop over portrait poster
        assertNull(movie.seriesId);
    }

    @Test
    public void mergeSkipsNextUpForSeriesBeingResumedAndCaps() throws Exception {
        List<JellyfinParser.Card> resume = JellyfinParser.cards(RESUME);
        List<JellyfinParser.Card> next = JellyfinParser.cards("{\"Items\":["
                + "{\"Id\":\"" + NEXT + "\",\"Type\":\"Episode\",\"Name\":\"E2\",\"SeriesName\":\"Severance\",\"SeriesId\":\"" + SERIES + "\"},"
                + "{\"Id\":\"66666666666666666666666666666666\",\"Type\":\"Episode\",\"Name\":\"E5\",\"SeriesName\":\"Andor\",\"SeriesId\":\"" + OTHER_SERIES + "\"}]}");
        List<JellyfinParser.Card> merged = JellyfinParser.merge(resume, next, 12);
        assertEquals(3, merged.size());
        assertEquals("Andor", merged.get(2).title);
        assertEquals(0f, merged.get(2).progress, 0f);
        assertEquals(1, JellyfinParser.merge(resume, next, 1).size());
        assertEquals(0, JellyfinParser.merge(Collections.emptyList(), Collections.emptyList(), 5).size());
    }

    @Test
    public void quickConnectAndSignIn() throws Exception {
        JellyfinParser.QuickConnect qc = JellyfinParser.quickConnect(
                "{\"Secret\":\"s3cr3t\",\"Code\":\"123456\",\"Authenticated\":false}");
        assertEquals("123456", qc.code);
        assertEquals("s3cr3t", qc.secret);
        assertTrue(!qc.authenticated);
        String[] r = JellyfinParser.signIn("{\"AccessToken\":\"tok\",\"User\":{\"Id\":\"" + EP + "\",\"Name\":\"Sam\"}}");
        assertEquals(Arrays.asList("tok", EP, "Sam"), Arrays.asList(r));
        assertNull(JellyfinParser.signIn("{\"User\":{\"Id\":\"" + EP + "\"}}"));
    }

    @Test
    public void normalizesJellyfinUrls() {
        assertEquals("http://10.0.0.5:8096", JellyfinConfig.normalizeUrl("http://10.0.0.5:8096/web/index.html#!/home.html"));
        assertEquals("http://10.0.0.5:8096", JellyfinConfig.normalizeUrl(" http://10.0.0.5:8096/ "));
        assertEquals("https://jf.example.com/jellyfin", JellyfinConfig.normalizeUrl("https://jf.example.com/jellyfin/web/"));
        assertNull(JellyfinConfig.normalizeUrl("10.0.0.5:8096"));
    }
}
