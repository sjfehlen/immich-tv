package com.fehlenfusion.immichscreensaver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Shuffled order without repeats until every id has been shown, then reshuffles. */
final class Playlist {
    private final List<String> ids;
    private final Random rng;
    private int pos;

    Playlist(List<String> ids, Random rng) {
        this.ids = new ArrayList<>(ids);
        this.rng = rng;
        Collections.shuffle(this.ids, rng);
    }

    int size() {
        return ids.size();
    }

    /** Next id, or null if the list is empty. */
    String next() {
        int n = ids.size();
        if (n == 0) return null;
        if (pos >= n) {
            String last = ids.get(n - 1);
            Collections.shuffle(ids, rng);
            // Avoid showing the same photo twice in a row across a reshuffle.
            if (n > 1 && ids.get(0).equals(last)) Collections.swap(ids, 0, 1 + rng.nextInt(n - 1));
            pos = 0;
        }
        return ids.get(pos++);
    }
}
