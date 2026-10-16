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
import com.yagay.ybrowser.loader.catalog.PluginRepository;
import java.util.List;

/** Standalone module manager; Chromium itself is never rebuilt. */
public final class MainActivity extends Activity {
    private LinearLayout body;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (body != null) render();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(248, 249, 252));
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(26), dp(20), dp(35));
        scroll.addView(body);
        setContentView(scroll);

        title("YBrowser Loader", 26);
        paragraph("LSPosed API 102 · Cromite / Brave\n仅编译增强模块，不需要编译 Chromium。");
        title("浏览器", 19);
        browserRow("Cromite", "org.cromite.cromite");
        browserRow("Brave", "com.brave.browser");
        paragraph("在 LSPosed 管理器中启用本模块并勾选对应浏览器。重启浏览器后，右下角出现模块入口。");

        title("已发现的独立插件", 19);
        List<PluginRepository.Entry> plugins = PluginRepository.scan(this, true);
        if (plugins.isEmpty()) {
            paragraph("尚未找到同签名的功能插件。\n先安装 CI 中提供的 Sample Plugin APK 进行测试。");
        } else {
            for (PluginRepository.Entry entry : plugins) {
                pluginRow(entry);
            }
        }

        Button refresh = action("重新扫描插件");
        refresh.setOnClickListener(v -> render());
        paragraph("安全规则：只有与 Loader APK 使用相同签名、并且手动启用的插件可以加载。安装其他来源的 APK 不会自动获得执行权限。");
        title("使用顺序", 19);
        paragraph("1. 安装 Loader 和示例插件 APK。\n2. 在 LSPosed 为 Loader 勾选 Cromite 或 Brave。\n3. 返回此页面启用示例插件。\n4. 强制停止并重启浏览器，点击 YBrowser 入口。\n5. 浏览器内出现绿色 Y+ 示例按钮即表示插件成功加载。");
        paragraph("关闭插件后需要重新进入浏览器才能彻底释放已加载的代码；重启浏览器更可靠。Chromium 原生标签栏和 C++ 功能尚未 Hook。");
    }

    private void browserRow(String label, String pkg) {
        LinearLayout row = horizontal();
        TextView text = text(label + (isInstalled(pkg) ? " · 已安装" : " · 未安装"), 15, false);
        row.addView(text, new LinearLayout.LayoutParams(0, dp(45), 1));
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText("打开");
        button.setEnabled(isInstalled(pkg));
        button.setOnClickListener(v -> {
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) startActivity(launch);
            else Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
        });
        row.addView(button);
        body.addView(row);
    }

    private void pluginRow(PluginRepository.Entry entry) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);
        LinearLayout row = horizontal();
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.addView(text(entry.label, 16, true));
        details.addView(text(entry.packageName, 11, false));
        details.addView(text("v" + entry.version, 11, false));
        row.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(this);
        toggle.setContentDescription("启用 " + entry.label);
        toggle.setChecked(entry.enabled);
        toggle.setOnCheckedChangeListener((view, checked) -> {
            PluginRepository.setEnabled(this, entry.packageName, checked);
            Toast.makeText(this, checked ? "已启用：返回浏览器生效" : "已停用：重启浏览器后完全卸载",
                    Toast.LENGTH_SHORT).show();
        });
        row.addView(toggle);
        card.addView(row);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.bottomMargin = dp(10);
        body.addView(card, cp);
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private Button action(String label) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        body.addView(button);
        return button;
    }

    private void title(String value, int sp) {
        TextView title = text(value, sp, true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(14);
        p.bottomMargin = dp(8);
        body.addView(title, p);
    }

    private void paragraph(String value) {
        TextView line = text(value, 14, false);
        line.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(9);
        body.addView(line, p);
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView result = new TextView(this);
        result.setText(value);
        result.setTextSize(sp);
        result.setTextColor(bold ? Color.rgb(24, 41, 64) : Color.rgb(75, 83, 96));
        if (bold) result.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        result.setGravity(Gravity.CENTER_VERTICAL);
        return result;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
