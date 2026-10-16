package com.yagay.ybrowser.controls;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import com.yagay.ybrowser.api.BrowserFeature;

/**
 * Real browser controls, not a placeholder: reads current Chromium TabModel,
 * draws a scrollable tab strip, selects/closes tabs and provides navigation.
 *
 * UI is a detachable floating strip. It deliberately does not overwrite the
 * native Chromium tab strip or mutate renderer/C++ objects.
 */
public final class BrowserControlsFeature implements BrowserFeature {
    private static final String TAG = "ybrowser.controls.strip";

    @Override public void onBrowserActivityResumed(Activity activity, String browserPackage) {
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) return;
        FrameLayout root = (FrameLayout) content;
        if (root.findViewWithTag(TAG) != null) return;
        Strip strip = new Strip(activity, root);
        strip.setTag(TAG);
        root.addView(strip, strip.layoutParams());
    }

    private static final class Strip extends LinearLayout {
        private static final String PREF = "ybrowser.controls.settings";
        private final Activity activity;
        private final FrameLayout owner;
        private final SharedPreferences prefs;
        private final LinearLayout tabItems;
        private final TextView grip, back, forward, refresh, add, menu;
        private final HorizontalScrollView scroller;
        private boolean minimized;
        private String lastSignature = "";
        private final Runnable ticker = new Runnable() {
            @Override public void run() {
                if (!isAttachedToWindow() || activity.isDestroyed()
                        || activity.isFinishing()) return;
                if (activity.hasWindowFocus()) refreshFromModel();
                postDelayed(this, 1000);
            }
        };

        Strip(Activity activity, FrameLayout owner) {
            super(activity);
            this.activity = activity;
            this.owner = owner;
            prefs = activity.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            minimized = prefs.getBoolean("minimized", false);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(4), dp(3), dp(4), dp(3));
            setElevation(dp(7));
            setBackground(rounded(Color.rgb(27, 34, 49), dp(13)));
            grip = action("Y", "Expand or collapse YBrowser controls", () -> {
                minimized = !minimized;
                prefs.edit().putBoolean("minimized", minimized).apply();
                updateVisibility();
            });
            grip.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            back = action("‹", "Back", () -> runNavigation("back"));
            forward = action("›", "Forward", () -> runNavigation("forward"));
            refresh = action("⟳", "Reload", () -> runNavigation("reload"));
            scroller = new HorizontalScrollView(activity);
            scroller.setHorizontalScrollBarEnabled(false);
            scroller.setFillViewport(false);
            tabItems = new LinearLayout(activity);
            tabItems.setOrientation(HORIZONTAL);
            tabItems.setGravity(Gravity.CENTER_VERTICAL);
            scroller.addView(tabItems, new HorizontalScrollView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
            addView(scroller, new LayoutParams(0, dp(40), 1f));
            add = action("+", "New tab", () -> execute(ChromiumTabBridge.newTab(activity)));
            menu = action("⋮", "Tab strip settings", this::showMenu);
            applyHeight();
            updateVisibility();
            refreshFromModel();
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            removeCallbacks(ticker);
            post(ticker);
        }

        @Override protected void onDetachedFromWindow() {
            removeCallbacks(ticker);
            super.onDetachedFromWindow();
        }

        FrameLayout.LayoutParams layoutParams() {
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, dp(panelHeight()),
                    Gravity.BOTTOM);
            p.leftMargin = dp(8);
            p.rightMargin = dp(8);
            if (prefs.getBoolean("top", false)) {
                p.gravity = Gravity.TOP;
                // 64dp avoids the system status bar but cannot guarantee avoiding
                // a custom Chromium toolbar in all variants. User can switch back.
                p.topMargin = dp(64);
            } else {
                p.bottomMargin = dp(100);
            }
            return p;
        }

        private int panelHeight() {
            return minimized ? 42 : Math.max(42, Math.min(64,
                    prefs.getInt("height", 48)));
        }

        private void applyHeight() {
            ViewGroup.LayoutParams current = getLayoutParams();
            if (current instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams p = layoutParams();
                setLayoutParams(p);
            }
        }

        private TextView action(String symbol, String desc, Runnable callback) {
            TextView view = new TextView(activity);
            view.setText(symbol);
            view.setTextSize(20);
            view.setTextColor(Color.WHITE);
            view.setGravity(Gravity.CENTER);
            view.setContentDescription(desc);
            view.setMinWidth(dp(34));
            view.setOnClickListener(v -> callback.run());
            addView(view, new LayoutParams(dp(34), dp(40)));
            return view;
        }

        private void updateVisibility() {
            back.setVisibility(minimized ? GONE : VISIBLE);
            forward.setVisibility(minimized ? GONE : VISIBLE);
            refresh.setVisibility(minimized ? GONE : VISIBLE);
            scroller.setVisibility(minimized ? GONE : VISIBLE);
            add.setVisibility(minimized ? GONE : VISIBLE);
            menu.setVisibility(minimized ? GONE : VISIBLE);
            FrameLayout.LayoutParams p = layoutParams();
            if (minimized) {
                p.width = dp(44);
                p.gravity = (prefs.getBoolean("top", false) ? Gravity.TOP : Gravity.BOTTOM)
                        | Gravity.RIGHT;
            }
            setLayoutParams(p);
        }

        private void refreshFromModel() {
            ChromiumTabBridge.Snapshot state = ChromiumTabBridge.snapshot(activity);
            back.setAlpha(state != null && state.back ? 1f : 0.4f);
            forward.setAlpha(state != null && state.forward ? 1f : 0.4f);
            add.setAlpha(state == null ? 0.4f : 1f);
            if (state == null) {
                if (!lastSignature.equals("unavailable")) {
                    lastSignature = "unavailable";
                    tabItems.removeAllViews();
                    TextView hint = tabLabel("Chromium API unavailable", false);
                    tabItems.addView(hint);
                }
                return;
            }
            StringBuilder signature = new StringBuilder();
            signature.append(state.count).append(':').append(state.selected);
            for (ChromiumTabBridge.TabInfo item : state.tabs) {
                signature.append('|').append(item.index).append('=').append(item.title);
            }
            if (signature.toString().equals(lastSignature)) return;
            lastSignature = signature.toString();
            tabItems.removeAllViews();
            int selectedPosition = -1;
            for (ChromiumTabBridge.TabInfo item : state.tabs) {
                LinearLayout chip = new LinearLayout(activity);
                chip.setGravity(Gravity.CENTER_VERTICAL);
                chip.setOrientation(HORIZONTAL);
                chip.setBackground(rounded(item.index == state.selected
                        ? Color.rgb(57, 103, 190) : Color.rgb(47, 57, 75), dp(8)));
                LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                        dp(145), dp(35));
                chipParams.rightMargin = dp(3);
                TextView title = tabLabel(item.title, item.index == state.selected);
                title.setOnClickListener(v -> execute(ChromiumTabBridge.select(activity, item.index)));
                chip.addView(title, new LinearLayout.LayoutParams(0, dp(35), 1f));
                TextView close = tabLabel("×", false);
                close.setGravity(Gravity.CENTER);
                close.setContentDescription("Close tab " + item.title);
                close.setOnClickListener(v -> execute(
                        ChromiumTabBridge.closeTab(activity, item.tab)));
                chip.addView(close, new LinearLayout.LayoutParams(dp(25), dp(35)));
                tabItems.addView(chip, chipParams);
                if (item.index == state.selected) {
                    selectedPosition = tabItems.getChildCount() - 1;
                }
            }
            if (selectedPosition >= 0) {
                final int index = selectedPosition;
                scroller.post(() -> {
                    if (index < tabItems.getChildCount()) {
                        View selected = tabItems.getChildAt(index);
                        scroller.smoothScrollTo(Math.max(0, selected.getLeft() - dp(60)), 0);
                    }
                });
            }
        }

        private TextView tabLabel(String title, boolean selected) {
            TextView label = new TextView(activity);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER_VERTICAL);
            label.setText(title);
            label.setTextSize(12);
            label.setTextColor(selected ? Color.WHITE : Color.rgb(226, 229, 235));
            label.setPadding(dp(7), 0, dp(2), 0);
            return label;
        }

        private void runNavigation(String command) {
            execute(ChromiumTabBridge.navigate(activity, command));
        }

        private void execute(boolean ok) {
            if (!ok) {
                Toast.makeText(activity, "Not supported in this browser version",
                        Toast.LENGTH_SHORT).show();
            }
            postDelayed(this::refreshFromModel, 170);
        }

        private void showMenu() {
            PopupMenu popup = new PopupMenu(activity, menu);
            popup.getMenu().add(0, 1, 0, "New tab");
            popup.getMenu().add(0, 2, 1,
                    prefs.getBoolean("top", false) ? "Place strip at bottom" : "Place strip at top");
            popup.getMenu().add(0, 3, 2, "Increase strip height");
            popup.getMenu().add(0, 4, 3, "Decrease strip height");
            popup.getMenu().add(0, 5, 4, "Minimize strip");
            popup.setOnMenuItemClickListener(item -> {
                switch (item.getItemId()) {
                    case 1: execute(ChromiumTabBridge.newTab(activity)); break;
                    case 2:
                        prefs.edit().putBoolean("top",
                                !prefs.getBoolean("top", false)).apply();
                        updateVisibility();
                        break;
                    case 3:
                        prefs.edit().putInt("height",
                                Math.min(64, prefs.getInt("height", 48) + 4)).apply();
                        applyHeight();
                        break;
                    case 4:
                        prefs.edit().putInt("height",
                                Math.max(42, prefs.getInt("height", 48) - 4)).apply();
                        applyHeight();
                        break;
                    case 5:
                        minimized = true;
                        prefs.edit().putBoolean("minimized", true).apply();
                        updateVisibility();
                        break;
                    default: return false;
                }
                return true;
            });
            popup.show();
        }

        private GradientDrawable rounded(int color, int radius) {
            GradientDrawable drawable = new GradientDrawable();
            drawable.setColor(color);
            drawable.setCornerRadius(radius);
            return drawable;
        }

        private int dp(int dp) {
            return Math.round(dp * getResources().getDisplayMetrics().density);
        }
    }
}
