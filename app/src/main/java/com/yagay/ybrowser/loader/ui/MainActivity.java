package com.yagay.ybrowser.loader.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.yagay.ybrowser.loader.settings.BrowserSettingsProvider;

/**
 * All-in-one UI: one installable APK contains settings, LSPosed entry and
 * Chromium tab controls. The targeted browser remains installed separately.
 */
public final class MainActivity extends Activity {
    private LinearLayout body;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        render();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(247, 249, 252));
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(25), dp(20), dp(30));
        scroll.addView(body);
        setContentView(scroll);

        title("YBrowser", 27);
        paragraph("一体化 LSPosed 浏览器增强应用 · API 102");
        paragraph("无需额外安装 Loader、Browser Controls 或 Sample Plugin。"
                + "不需要自行编译 Chromium，但需要设备已有 Cromite 或 Brave。");

        title("功能开关", 19);
        LinearLayout settingsCard = new LinearLayout(this);
        settingsCard.setPadding(dp(15), dp(12), dp(15), dp(12));
        settingsCard.setGravity(Gravity.CENTER_VERTICAL);
        settingsCard.setBackground(cardBackground());
        TextView label = text("启用多标签栏", 16, true);
        settingsCard.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch tabs = new Switch(this);
        tabs.setContentDescription("启用多标签栏");
        tabs.setChecked(getSharedPreferences(BrowserSettingsProvider.PREFS,
                MODE_PRIVATE).getBoolean(BrowserSettingsProvider.TABS_ENABLED, true));
        tabs.setOnCheckedChangeListener((button, enabled) -> {
            getSharedPreferences(BrowserSettingsProvider.PREFS, MODE_PRIVATE)
                    .edit().putBoolean(BrowserSettingsProvider.TABS_ENABLED, enabled).apply();
            Toast.makeText(this, "设置已保存，返回浏览器后刷新",
                    Toast.LENGTH_SHORT).show();
        });
        settingsCard.addView(tabs);
        body.addView(settingsCard);
        paragraph("标签栏包括切换、新建、关闭、前进、后退和刷新，"
                + "可以在浏览器标签栏的 ⋮ 菜单中调节位置和大小、复制 Chromium 接口诊断。");

        title("目标浏览器", 19);
        browserRow("Cromite", "org.cromite.cromite");
        browserRow("Brave", "com.brave.browser");

        title("安装和启用", 19);
        paragraph("1. 安装本次 CI 的唯一 APK：app-debug.apk。\n"
                + "2. 在 LSPosed 启用 YBrowser，选择已安装的 Cromite 或 Brave。\n"
                + "3. 完全关闭并重新打开目标浏览器。\n"
                + "4. 在浏览器查看标签栏；如果失败，打开 ⋮ → 浏览器接口诊断复制报告。");
        paragraph("请注意：本应用是浏览器增强与 Hook 管理工具，"
                + "不是独立 Chromium 浏览器。不同版本仍需通过运行时诊断验证。");
    }

    private void browserRow(String name, String pkg) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        boolean installed = isInstalled(pkg);
        row.addView(text(name + (installed ? " · 已安装" : " · 未安装"),
                15, false), new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button open = new Button(this);
        open.setAllCaps(false);
        open.setText("打开");
        open.setEnabled(installed);
        open.setOnClickListener(v -> {
            Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) startActivity(intent);
            else Toast.makeText(this, "无法打开目标浏览器", Toast.LENGTH_SHORT).show();
        });
        row.addView(open);
        body.addView(row);
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private GradientDrawable cardBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(12));
        return bg;
    }

    private void title(String content, int size) {
        TextView v = text(content, size, true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(12);
        p.bottomMargin = dp(10);
        body.addView(v, p);
    }

    private void paragraph(String content) {
        TextView v = text(content, 14, false);
        v.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(12);
        body.addView(v, p);
    }

    private TextView text(String content, int size, boolean bold) {
        TextView v = new TextView(this);
        v.setText(content);
        v.setTextSize(size);
        v.setTextColor(bold ? Color.rgb(23, 43, 66) : Color.rgb(65, 76, 90));
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private int dp(int size) {
        return Math.round(size * getResources().getDisplayMetrics().density);
    }
}
