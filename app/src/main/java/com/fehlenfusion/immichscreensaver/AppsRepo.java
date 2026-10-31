package com.fehlenfusion.immichscreensaver;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Launchable TV apps and the user's ordered favourites for the home screen. */
final class AppsRepo {
    private static final String PREFS = "home";
    private static final String KEY_FAVORITES = "favorites";

    /** Packages that live in the shortcut row instead, or are system plumbing. */
    private static final String[] NOT_DEFAULT_FAVORITES = {
            "com.android.vending",
            "com.android.tv.settings",
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tungsten.setupwraith",
    };

    static final class App {
        final String pkg;
        final String label;
        final ComponentName component;
        final boolean leanback;
        final boolean userFacing; // installed by the user or an updated preinstall (YouTube etc.)
        Drawable banner;
        Drawable icon;

        App(String pkg, String label, ComponentName component, boolean leanback, boolean userFacing) {
            this.pkg = pkg;
            this.label = label;
            this.component = component;
            this.leanback = leanback;
            this.userFacing = userFacing;
        }

        Intent launchIntent() {
            return new Intent(Intent.ACTION_MAIN)
                    .addCategory(leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                    .setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }
    }

    private AppsRepo() {}

    /** All launchable apps, TV (leanback) entries preferred, sorted by label. Loads drawables: call off the main thread. */
    static List<App> loadApps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, App> byPkg = new LinkedHashMap<>();
        addAll(ctx, pm, byPkg, Intent.CATEGORY_LEANBACK_LAUNCHER, true);
        addAll(ctx, pm, byPkg, Intent.CATEGORY_LAUNCHER, false);
        List<App> apps = new ArrayList<>(byPkg.values());
        final Collator collator = Collator.getInstance();
        Collections.sort(apps, (a, b) -> collator.compare(a.label, b.label));
        return apps;
    }

    private static void addAll(Context ctx, PackageManager pm, Map<String, App> into, String category, boolean leanback) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> list;
        try {
            list = pm.queryIntentActivities(q, 0);
        } catch (RuntimeException e) {
            return;
        }
        for (ResolveInfo ri : list) {
            String pkg = ri.activityInfo.packageName;
            if (into.containsKey(pkg)) continue;
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean updated = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            CharSequence label = ri.loadLabel(pm);
            App app = new App(pkg, label == null ? pkg : label.toString(),
                    new ComponentName(pkg, ri.activityInfo.name), leanback, !system || updated);
            try {
                app.banner = ri.activityInfo.loadBanner(pm);
                if (app.banner == null) app.banner = ai.loadBanner(pm);
                if (app.banner == null) app.icon = ri.loadIcon(pm);
            } catch (RuntimeException | OutOfMemoryError ignored) {
                // a broken resource in one app must not break the home screen
            }
            into.put(pkg, app);
        }
    }

    /** Favourite packages in order. First run: the user-facing apps, alphabetically. */
    static List<String> favorites(Context ctx, List<App> apps) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = p.getString(KEY_FAVORITES, null);
        List<String> out = new ArrayList<>();
        if (saved != null) {
            for (String s : saved.split(",")) if (!s.isEmpty() && !out.contains(s)) out.add(s);
            return out;
        }
        Set<String> launchers = homePackages(ctx);
        for (App a : apps) {
            if (a.userFacing && !a.pkg.equals(ctx.getPackageName()) && !isExcluded(a.pkg)
                    && !launchers.contains(a.pkg)) {
                out.add(a.pkg);
            }
        }
        return out;
    }

    static void saveFavorites(Context ctx, List<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (String s : pkgs) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_FAVORITES, sb.toString()).apply();
    }

    /** Packages that provide a Home screen (other launchers): never default favourites. */
    private static Set<String> homePackages(Context ctx) {
        Set<String> out = new HashSet<>();
        try {
            for (ResolveInfo ri : ctx.getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
                out.add(ri.activityInfo.packageName);
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    private static boolean isExcluded(String pkg) {
        for (String s : NOT_DEFAULT_FAVORITES) if (s.equals(pkg)) return true;
        return false;
    }

    /** Moves pkg by delta within the list (bounded). Pure; unit-tested. */
    static List<String> move(List<String> list, String pkg, int delta) {
        List<String> out = new ArrayList<>(list);
        int i = out.indexOf(pkg);
        if (i < 0) return out;
        int j = Math.max(0, Math.min(out.size() - 1, i + delta));
        out.remove(i);
        out.add(j, pkg);
        return out;
    }
}
