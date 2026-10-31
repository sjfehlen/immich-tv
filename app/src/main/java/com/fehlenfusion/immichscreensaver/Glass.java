package com.fehlenfusion.immichscreensaver;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Lists in the home screen's glass style (settings, menus): rows on a frosted card, the
 * focused row lit by a soft fill. No outlines, same radius and type as the home tiles.
 */
final class Glass {
    /** Inset between a card's edge and its rows, so the rows' corners nest inside the card's. */
    static final int CARD_PAD_DP = 6;
    static final int ROW_RADIUS_DP = Tiles.RADIUS_DP - 2;

    private Glass() {}

    /** Tint for a card sitting on an already-blurred backdrop: reads as the same frosted glass. */
    static Drawable card(Context c) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x4D262B38, 0x66181B22});
        g.setCornerRadius(Tiles.dp(c, Tiles.RADIUS_DP));
        return g;
    }

    /** A vertical card that holds rows. */
    static LinearLayout cardLayout(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = Tiles.dp(c, CARD_PAD_DP);
        card.setPadding(p, p, p, p);
        return card;
    }

    /** Small muted heading above a card. */
    static TextView header(Context c, String s) {
        TextView t = Tiles.label(c, s, 14, 0xA6FFFFFF);
        t.setTypeface(Tiles.MEDIUM);
        t.setLetterSpacing(0.03f);
        t.setPadding(Tiles.dp(c, 14), Tiles.dp(c, 22), 0, Tiles.dp(c, 8));
        return t;
    }

    static final class Row {
        final LinearLayout view;
        final TextView title, value, detail;

        Row(LinearLayout view, TextView title, TextView value, TextView detail) {
            this.view = view;
            this.title = title;
            this.value = value;
            this.detail = detail;
        }

        Row value(CharSequence v) {
            value.setText(v);
            return this;
        }

        /** Secondary line under the title; hidden when empty. */
        Row detail(CharSequence d) {
            detail.setText(d);
            detail.setVisibility(d == null || d.length() == 0 ? View.GONE : View.VISIBLE);
            return this;
        }
    }

    /** Title on the left, value on the right, optional detail line under the title. */
    static Row row(Context c, String title, View.OnClickListener onClick) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setClickable(true);
        r.setMinimumHeight(Tiles.dp(c, 44));
        r.setPadding(Tiles.dp(c, 14), Tiles.dp(c, 9), Tiles.dp(c, 14), Tiles.dp(c, 9));
        r.setBackground(rowBackground(c));
        r.setOnClickListener(onClick);

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = Tiles.label(c, title, 17, 0xFFFFFFFF);
        t.setTextColor(states(0xFFFFFFFF, 0xE6FFFFFF));
        t.setDuplicateParentStateEnabled(true);
        text.addView(t);
        TextView d = Tiles.label(c, "", 13, 0x99FFFFFF);
        d.setLineSpacing(0f, 1.15f);
        d.setPadding(0, Tiles.dp(c, 2), 0, 0);
        d.setVisibility(View.GONE);
        text.addView(d);
        r.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView v = Tiles.label(c, "", 16, 0xFFFFFFFF);
        v.setTextColor(states(0xF2FFFFFF, 0x99FFFFFF));
        v.setDuplicateParentStateEnabled(true);
        v.setGravity(Gravity.END);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setMaxWidth(Tiles.dp(c, 260));
        v.setPadding(Tiles.dp(c, 16), 0, 0, 0);
        r.addView(v, new LinearLayout.LayoutParams(-2, -2));
        return new Row(r, t, v, d);
    }

    /** Adds a row to a card with the small gap rows keep between them. */
    static Row add(LinearLayout card, Row row) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        if (card.getChildCount() > 0) lp.topMargin = Tiles.dp(card.getContext(), 2);
        card.addView(row.view, lp);
        return row;
    }

    static ColorStateList states(int focused, int normal) {
        return new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{focused, normal});
    }

    private static StateListDrawable rowBackground(Context c) {
        float r = Tiles.dp(c, ROW_RADIUS_DP);
        GradientDrawable lit = new GradientDrawable();
        lit.setColor(0x38FFFFFF);
        lit.setCornerRadius(r);
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(0x52FFFFFF);
        pressed.setCornerRadius(r);
        GradientDrawable selected = new GradientDrawable();
        selected.setColor(0x17FFFFFF);
        selected.setCornerRadius(r);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(new int[]{android.R.attr.state_focused}, lit);
        s.addState(new int[]{android.R.attr.state_selected}, selected);
        s.addState(new int[]{}, new ColorDrawable(0));
        s.setEnterFadeDuration(120);
        s.setExitFadeDuration(160);
        return s;
    }
}
