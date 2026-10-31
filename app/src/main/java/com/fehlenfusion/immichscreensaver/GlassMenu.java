package com.fehlenfusion.immichscreensaver;

import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A small frosted menu that floats beside the tile it belongs to, over the home screen, instead
 * of a stock dialog. Focus stays inside it; Back or Menu closes it.
 */
final class GlassMenu extends FrameLayout {
    interface OnPick {
        void pick(int which);
    }

    private final FrameLayout root;
    private final LinearLayout panel;
    private final Frost frost;
    private final View restoreFocus;
    private boolean closing;
    /** Key that is down inside the menu; a key-up we never saw go down (the one that opened us) is ignored. */
    private int downKey = -1;

    /**
     * @param backdrop the photo layer behind the menu, blurred into its glass
     * @param anchor   the tile the menu belongs to; the menu sits just above it
     */
    static GlassMenu show(FrameLayout root, View backdrop, View anchor, String title,
                          String[] items, OnPick onPick) {
        GlassMenu m = new GlassMenu(root, backdrop, anchor, title, items, onPick);
        root.addView(m, new FrameLayout.LayoutParams(-1, -1));
        m.open();
        return m;
    }

    private GlassMenu(FrameLayout root, View backdrop, View anchor, String title,
                      String[] items, OnPick onPick) {
        super(root.getContext());
        Context c = getContext();
        this.root = root;
        this.restoreFocus = anchor;
        setBackgroundColor(0x40000000);
        setClickable(true); // a tap outside the panel closes
        setOnClickListener(v -> close(null));

        panel = Glass.cardLayout(c);
        panel.setClickable(true);
        panel.setMinimumWidth(Tiles.dp(c, 240));
        Tiles.rounded(panel, Tiles.dp(c, Tiles.RADIUS_DP));
        panel.setElevation(Tiles.dp(c, 22));
        if (title != null) {
            TextView t = Tiles.label(c, title, 13, 0xA6FFFFFF);
            t.setTypeface(Tiles.MEDIUM);
            t.setSingleLine(true);
            t.setPadding(Tiles.dp(c, 14), Tiles.dp(c, 6), Tiles.dp(c, 14), Tiles.dp(c, 4));
            panel.addView(t);
        }
        for (int i = 0; i < items.length; i++) {
            final int which = i;
            Glass.add(panel, Glass.row(c, items[i], v -> close(() -> onPick.pick(which))));
        }
        addView(panel, place(anchor));
        frost = new Frost(panel, Tiles.dp(c, Tiles.RADIUS_DP));
        frost.setSource(backdrop);
    }

    /** Just above the anchor, lined up with its nearer edge, inside the screen's margins. */
    private FrameLayout.LayoutParams place(View anchor) {
        int[] a = new int[2], r = new int[2];
        anchor.getLocationInWindow(a);
        root.getLocationInWindow(r);
        int left = a[0] - r[0], top = a[1] - r[1];
        int right = left + Math.round(anchor.getWidth() * anchor.getScaleX());
        int w = root.getWidth(), h = root.getHeight();
        int gutter = Tiles.dp(getContext(), 32);
        boolean onRight = (left + right) / 2 > w / 2;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | (onRight ? Gravity.END : Gravity.START));
        lp.bottomMargin = Math.max(gutter, h - top + Tiles.dp(getContext(), 14));
        if (onRight) lp.rightMargin = Math.max(gutter, w - right);
        else lp.leftMargin = Math.max(gutter, left);
        return lp;
    }

    private void open() {
        setAlpha(0f);
        panel.setScaleX(0.94f);
        panel.setScaleY(0.94f);
        panel.setTranslationY(Tiles.dp(getContext(), 10));
        animate().alpha(1f).setDuration(160);
        panel.post(() -> {
            panel.setPivotX(((FrameLayout.LayoutParams) panel.getLayoutParams()).gravity
                    == (Gravity.BOTTOM | Gravity.END) ? panel.getWidth() : 0);
            panel.setPivotY(panel.getHeight());
            panel.animate().scaleX(1f).scaleY(1f).translationY(0f)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).setDuration(220);
        });
        int first = panel.getChildCount() > 0 && !panel.getChildAt(0).isFocusable() ? 1 : 0;
        if (panel.getChildCount() > first) panel.getChildAt(first).requestFocus();
    }

    /** Closes, gives focus back to the anchor, then runs {@code after}. */
    void close(Runnable after) {
        if (closing) return;
        closing = true;
        frost.clear();
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) parent.removeView(this);
        if (restoreFocus != null && restoreFocus.isAttachedToWindow()) restoreFocus.requestFocus();
        if (after != null) after.run();
    }

    boolean isOpen() {
        return !closing && getParent() != null;
    }

    /** Arrow keys never leave the menu. */
    @Override
    public View focusSearch(View focused, int direction) {
        View next = super.focusSearch(focused, direction);
        for (View p = next; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p == this) return next;
        }
        return focused;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MENU) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) downKey = k;
            else if (e.getAction() == KeyEvent.ACTION_UP && downKey == k) close(null);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
