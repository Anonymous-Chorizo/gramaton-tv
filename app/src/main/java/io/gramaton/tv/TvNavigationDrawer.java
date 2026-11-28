package io.gramaton.tv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** A TV navigation rail that expands over the catalogue while it has focus. */
final class TvNavigationDrawer extends LinearLayout {
    static final int RAIL_WIDTH = 76;
    private static final int DRAWER_WIDTH = 196;
    private static final int BACKGROUND = 0xff090e17;
    private static final int ACTIVE = 0xff22334c;
    private static final int WHITE = 0xfff5f7fb;
    private static final int MUTED = 0xffaebbd0;

    private final List<Button> destinations = new ArrayList<>();
    private final TextView wordmark;
    private final View scrim;
    private final Runnable focusContent;
    private Button selected;

    TvNavigationDrawer(Context context, View scrim, Runnable focusContent) {
        super(context);
        this.scrim = scrim;
        this.focusContent = focusContent;
        setOrientation(VERTICAL);
        setPadding(dp(14), dp(18), dp(14), dp(18));
        setBackgroundColor(BACKGROUND);

        LinearLayout brand = new LinearLayout(context);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(context);
        mark.setImageResource(R.drawable.tv_icon);
        LayoutParams markSize = new LayoutParams(dp(26), dp(26));
        markSize.setMargins(dp(11), 0, dp(11), 0);
        brand.addView(mark, markSize);
        wordmark = new TextView(context);
        wordmark.setText("Gramaton");
        wordmark.setTextColor(WHITE);
        wordmark.setTextSize(19);
        wordmark.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        wordmark.setVisibility(GONE);
        brand.addView(wordmark);
        addView(brand, new LayoutParams(-1, dp(46)));
    }

    Button addDestination(String label, int icon, Runnable action) {
        Button button = new Button(getContext());
        button.setContentDescription(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setSingleLine();
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setPaddingRelative(dp(12), 0, dp(12), 0);
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        button.setCompoundDrawablePadding(dp(12));
        button.setFocusableInTouchMode(true);
        button.setDefaultFocusHighlightEnabled(false);
        button.setStateListAnimator(null);
        button.setOnFocusChangeListener((view, focused) -> post(this::updateAppearance));
        button.setOnClickListener(view -> action.run());
        button.setOnKeyListener((view, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                focusContent.run();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) return true;
            if (key != KeyEvent.KEYCODE_DPAD_UP && key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            int step = key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
            for (int next = destinations.indexOf(button) + step; next >= 0 && next < destinations.size(); next += step) {
                Button candidate = destinations.get(next);
                if (candidate.getVisibility() == VISIBLE && candidate.requestFocus()) break;
            }
            return true;
        });
        LayoutParams size = new LayoutParams(-1, dp(40));
        size.topMargin = dp(4);
        destinations.add(button);
        addView(button, size);
        updateButton(button, false);
        return button;
    }

    void separateActions() { addView(new View(getContext()), new LayoutParams(1, 0, 1)); }

    void select(String label) {
        for (Button button : destinations) {
            if (label.contentEquals(button.getContentDescription())) selected = button;
        }
        updateAppearance();
    }

    void focusSelected() { if (selected != null) selected.requestFocus(); }

    void focusDestination(String label) {
        for (Button button : destinations) {
            if (label.contentEquals(button.getContentDescription())) {
                button.requestFocus();
                return;
            }
        }
    }

    private void updateAppearance() {
        boolean expanded = hasFocus();
        int width = dp(expanded ? DRAWER_WIDTH : RAIL_WIDTH);
        if (getLayoutParams().width != width) {
            getLayoutParams().width = width;
            requestLayout();
        }
        scrim.setVisibility(expanded ? VISIBLE : GONE);
        wordmark.setVisibility(expanded ? VISIBLE : GONE);
        for (Button button : destinations) updateButton(button, expanded);
    }

    private void updateButton(Button button, boolean expanded) {
        boolean focused = button.hasFocus();
        boolean active = button == selected;
        int color = focused ? BACKGROUND : active ? WHITE : MUTED;
        button.setText(expanded ? button.getContentDescription() : "");
        button.setTextColor(color);
        button.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(color));
        button.setSelected(active);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(20));
        shape.setColor(focused ? WHITE : active ? ACTIVE : Color.TRANSPARENT);
        button.setBackground(shape);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
