package com.fehlenfusion.immichscreensaver;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

/**
 * Home Assistant scripts that sit in the app row like apps. Their keys share the favourites
 * list with package names, so moving and removing them works the same way.
 */
final class HaScripts {
    static final String PREFIX = "ha:";
    private static final String PREFS = "home";
    private static final String KEY_ADDED = "haScriptsAdded";

    static final class Script {
        final String key;
        final String entityId;
        final String label;
        final int bannerRes;

        Script(String entityId, String label, int bannerRes) {
            this.key = PREFIX + entityId;
            this.entityId = entityId;
            this.label = label;
            this.bannerRes = bannerRes;
        }
    }

    static final Script[] ALL = {
            new Script("script.switch_setup", "Switch", R.drawable.banner_switch),
            new Script("script.rog_ally_setup", "ROG Ally X", R.drawable.banner_rog_ally),
            new Script("script.play_station_setup_2", "PlayStation", R.drawable.banner_playstation),
    };

    private HaScripts() {}

    /**
     * The official product photo if it's present locally (drawable-nodpi/&lt;banner&gt;_photo.png,
     * gitignored), otherwise the drawn banner.
     */
    static android.graphics.drawable.Drawable banner(Context ctx, Script s) {
        android.content.res.Resources r = ctx.getResources();
        int photo = r.getIdentifier(r.getResourceEntryName(s.bannerRes) + "_photo", "drawable", ctx.getPackageName());
        return ctx.getDrawable(photo != 0 ? photo : s.bannerRes);
    }

    static Script find(String key) {
        for (Script s : ALL) if (s.key.equals(key)) return s;
        return null;
    }

    /**
     * Adds the script tiles to the end of the favourites once. After that they behave like apps:
     * removing one from home keeps it removed. Returns true if the list changed.
     */
    static boolean addOnce(Context ctx, List<String> favorites) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean(KEY_ADDED, false)) return false;
        boolean changed = false;
        for (Script s : ALL) {
            if (!favorites.contains(s.key)) {
                favorites.add(s.key);
                changed = true;
            }
        }
        p.edit().putBoolean(KEY_ADDED, true).apply();
        return changed;
    }
}
