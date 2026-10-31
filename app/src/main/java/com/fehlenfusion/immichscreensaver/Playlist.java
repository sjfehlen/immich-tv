package com.fehlenfusion.immichscreensaver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Play order. Shuffle: random without repeats until every id has been shown, then reshuffles.
 * Otherwise the given order, looping.
 */
final class Playlist {
    private final List<String> ids;
    private final Random rng;
    private final boolean shuffle;
    private int pos;

    Playlist(List<String> ids, Random rng) {
        this(ids, rng, true);
    }

    Playlist(List<String> ids, Random rng, boolean shuffle) {
        this.ids = new ArrayList<>(ids);
        this.rng = rng;
        this.shuffle = shuffle;
        if (shuffle) Collections.shuffle(this.ids, rng);
    }

    /** Ids ordered for the given {@link Prefs} order (shuffle keeps the input order). */
    static List<String> ordered(List<AssetParser.Photo> photos, int order) {
        List<AssetParser.Photo> sorted = new ArrayList<>(photos);
        if (order == Prefs.ORDER_NEWEST) {
            Collections.sort(sorted, (a, b) -> b.takenAt.compareTo(a.takenAt));
        } else if (order == Prefs.ORDER_OLDEST) {
            Collections.sort(sorted, (a, b) -> a.takenAt.compareTo(b.takenAt));
        }
        return AssetParser.ids(sorted);
    }

    int size() {
        return ids.size();
    }

    /** Next id, or null if the list is empty. */
    String next() {
        int n = ids.size();
        if (n == 0) return null;
        if (pos >= n && !shuffle) {
            pos = 0;
        } else if (pos >= n) {
            String last = ids.get(n - 1);
            Collections.shuffle(ids, rng);
            // Avoid showing the same photo twice in a row across a reshuffle.
            if (n > 1 && ids.get(0).equals(last)) Collections.swap(ids, 0, 1 + rng.nextInt(n - 1));
            pos = 0;
        }
        return ids.get(pos++);
    }
}
