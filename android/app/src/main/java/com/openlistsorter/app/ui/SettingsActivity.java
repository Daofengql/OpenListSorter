package com.openlistsorter.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.openlistsorter.app.R;
import com.openlistsorter.app.data.ConnectionProfile;
import com.openlistsorter.app.data.ConnectionStore;
import com.openlistsorter.app.data.MediaTypeStore;
import com.openlistsorter.app.data.Models;
import com.openlistsorter.app.data.PreviewCache;
import com.openlistsorter.app.network.OpenListClient;

/** iPad-style settings: category navigation on the left, detail content on the right. */
public final class SettingsActivity extends Activity {
    public static final String EXTRA_LOGOUT = "settings_logout";
    public static final String EXTRA_CLEAR_SELECTIONS = "settings_clear_selections";
    public static final String EXTRA_CLASSIFICATION_ACTIVE = "classification_active";
    private static final int NAV_WIDTH_DP = 220;
    private LinearLayout detail;
    private TextView connectionStatus;
    private OpenListClient client;
    private ConnectionProfile profile;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        bars.show(WindowInsetsCompat.Type.systemBars());
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        client = new OpenListClient();
        profile = ConnectionStore.load(this);
        client.setBaseUrl(profile.endpoint);
        client.setToken(profile.token);
        buildUi();
        showConnection();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.color.page_background);
        applyInsets(root);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(4), dp(12), dp(4));
        MaterialButton back = button("返回");
        back.setIconResource(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        back.setOnClickListener(v -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(72), dp(48)));
        TextView title = heading("设置", 20);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(56)));

        LinearLayout split = new LinearLayout(this);
        split.setOrientation(isWide() ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(isWide() ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        navigation.setPadding(dp(8), dp(8), dp(8), dp(8));
        navigation.setBackgroundColor(getColorCompat(R.color.surface));
        detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.VERTICAL);
        detail.setPadding(dp(16), dp(12), dp(16), dp(16));
        ScrollView detailScroll = new ScrollView(this);
        detailScroll.addView(detail, new ScrollView.LayoutParams(-1, -2));

        addNavigation(navigation, "连接信息", "连接状态、Endpoint 与远端信息", this::showConnection);
        addNavigation(navigation, "媒体识别", "图片和视频自定义后缀", this::showMediaTypes);
        addNavigation(navigation, "性能与缓存", "预热并发、提交并发和缓存", this::showPerformance);
        addNavigation(navigation, "应用", "版本和当前会话", this::showAbout);
        if (isWide()) {
            split.addView(navigation, new LinearLayout.LayoutParams(dp(NAV_WIDTH_DP), -1));
            split.addView(detailScroll, new LinearLayout.LayoutParams(0, -1, 1));
        } else {
            split.addView(navigation, new LinearLayout.LayoutParams(-1, dp(72)));
            split.addView(detailScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        root.addView(split, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void addNavigation(LinearLayout parent, String title, String summary, Runnable action) {
        MaterialCardView row = new MaterialCardView(this);
        row.setRadius(dp(5));
        row.setCardElevation(0);
        row.setStrokeWidth(1);
        row.setStrokeColor(getColorCompat(R.color.outline_variant));
        row.setCardBackgroundColor(getColorCompat(R.color.surface));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(8), dp(12), dp(8));
        TextView titleView = text(title, getColorCompat(R.color.on_surface));
        titleView.setTextSize(14);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView summaryView = text(summary, getColorCompat(R.color.on_surface_variant));
        summaryView.setTextSize(11);
        summaryView.setMaxLines(2);
        content.addView(titleView);
        content.addView(summaryView);
        row.addView(content);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = isWide()
                ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(0, -1, 1);
        params.setMargins(0, 0, 0, dp(6));
        parent.addView(row, params);
    }

    private void resetDetail(String title, String summary) {
        detail.removeAllViews();
        detail.addView(heading(title, 22));
        detail.addView(text(summary, getColorCompat(R.color.on_surface_variant)));
        View divider = new View(this);
        divider.setBackgroundColor(getColorCompat(R.color.outline_variant));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, 1);
        dividerParams.setMargins(0, dp(12), 0, dp(12));
        detail.addView(divider, dividerParams);
    }

    private void showConnection() {
        resetDetail("连接信息", "当前 OpenList 连接和远端服务状态");
        TextView endpoint = text("Endpoint\n" + emptyAsUnknown(profile.endpoint), getColorCompat(R.color.on_surface));
        endpoint.setTextSize(15);
        detail.addView(endpoint);
        connectionStatus = text("正在读取连接状态...", getColorCompat(R.color.on_surface_variant));
        connectionStatus.setPadding(dp(4), dp(12), dp(4), dp(12));
        detail.addView(connectionStatus);
        MaterialButton refresh = button("刷新连接信息");
        refresh.setOnClickListener(v -> refreshConnection(endpoint));
        detail.addView(refresh, new LinearLayout.LayoutParams(-1, dp(48)));
        refreshConnection(endpoint);
    }

    private void refreshConnection(TextView endpoint) {
        if (connectionStatus == null) return;
        connectionStatus.setText("正在检测 Endpoint、延迟、IP 和远端版本...");
        client.fetchConnectionDiagnostics(diagnostics -> {
            if (connectionStatus == null || isFinishing()) return;
            connectionStatus.setText("状态: " + (diagnostics.connected ? "已连接" : "未连接")
                    + "\n延迟: " + (diagnostics.latencyMs >= 0 ? diagnostics.latencyMs + " ms" : "未知")
                    + "\nIP: " + emptyAsUnknown(diagnostics.ipAddress)
                    + "\n远端版本: " + emptyAsUnknown(diagnostics.version)
                    + "\n站点标题: " + emptyAsUnknown(diagnostics.siteTitle)
                    + (diagnostics.message.isEmpty() ? "" : "\n信息: " + diagnostics.message));
        });
    }

    private void showMediaTypes() {
        resetDetail("媒体识别", "把误命名的后缀加入对应列表后，列表和预览会强制按该类型解析。多个后缀可用逗号、空格或分号分隔。");
        TextInputEditText imageInput = input(MediaTypeStore.imageText(this));
        TextInputEditText videoInput = input(MediaTypeStore.videoText(this));
        detail.addView(labelled("强制按图片解析", imageInput));
        detail.addView(labelled("强制按视频解析", videoInput));
        detail.addView(text("内置图片: JPG、PNG、WebP、HEIC、AVIF 等\n内置视频: MP4、WebM、MKV、MOV、TS、OGV、3GP 等", getColorCompat(R.color.on_surface_variant)));
        MaterialButton save = button("保存媒体识别规则");
        save.setOnClickListener(v -> {
            boolean saved = MediaTypeStore.save(this, imageInput.getText().toString(), videoInput.getText().toString());
            Toast.makeText(this, saved ? "媒体识别规则已保存" : "同一个后缀不能同时归类为图片和视频",
                    Toast.LENGTH_SHORT).show();
        });
        detail.addView(save, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private void showPerformance() {
        resetDetail("性能与缓存", "调整预热和批量提交的并发数，查看缓存占用。全屏预览不再限制为 200 个文件。");
        android.content.SharedPreferences prefs = getSharedPreferences("task", MODE_PRIVATE);
        android.widget.NumberPicker preload = picker(1, 10, prefs.getInt("preloadConcurrency", 3));
        android.widget.NumberPicker submit = picker(1, 4, prefs.getInt("submitConcurrency", 2));
        detail.addView(settingRow("分页预热并发数", preload));
        detail.addView(settingRow("批量提交并发数", submit));
        PreviewCache.Stats stats = new PreviewCache(this).stats();
        detail.addView(text("缓存 " + stats.fileCount + " 项 · " + Models.formatBytes(stats.totalBytes)
                + " / " + Models.formatBytes(PreviewCache.DEFAULT_MAX_DISK_BYTES)
                + "\n原分辨率解码并发由设备内存自动决定", getColorCompat(R.color.on_surface_variant)));
        MaterialButton save = button("保存性能设置");
        save.setOnClickListener(v -> {
            prefs.edit().putInt("preloadConcurrency", preload.getValue())
                    .putInt("submitConcurrency", submit.getValue()).apply();
            Toast.makeText(this, "性能设置已保存", Toast.LENGTH_SHORT).show();
        });
        detail.addView(save, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private void showAbout() {
        resetDetail("应用", "OpenList Sorter Android");
        String version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception error) {
            version = "未知";
        }
        detail.addView(text("版本: " + version + "\n\n当前设置页使用 iPad 风格的左侧项目导航和右侧细则布局。\n\n视频播放使用 Media3，支持 HTTP Range 的服务器会按需读取视频片段。", getColorCompat(R.color.on_surface)));
        MaterialButton clearSelections = button("清空未提交选择");
        clearSelections.setOnClickListener(v -> {
            Intent result = new Intent();
            result.putExtra(EXTRA_CLEAR_SELECTIONS, true);
            setResult(RESULT_OK, result);
            finish();
        });
        detail.addView(clearSelections, new LinearLayout.LayoutParams(-1, dp(48)));
        MaterialButton logout = button("注销");
        logout.setTextColor(getColorCompat(R.color.error));
        logout.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle("确认注销")
                .setMessage("将断开连接并关闭自动登录，已保存的连接信息会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("注销", (dialog, which) -> {
                    Intent result = new Intent();
                    result.putExtra(EXTRA_LOGOUT, true);
                    setResult(RESULT_OK, result);
                    finish();
                }).show());
        detail.addView(logout, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private android.widget.NumberPicker picker(int min, int max, int value) {
        android.widget.NumberPicker picker = new android.widget.NumberPicker(this);
        picker.setMinValue(min);
        picker.setMaxValue(max);
        picker.setValue(Math.max(min, Math.min(max, value)));
        return picker;
    }

    private LinearLayout settingRow(String label, View control) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(label, getColorCompat(R.color.on_surface)), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(control, new LinearLayout.LayoutParams(dp(104), dp(96)));
        return row;
    }

    private TextInputLayout labelled(String label, View child) {
        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint(label);
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        layout.addView(child, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(4), 0, dp(8));
        layout.setLayoutParams(params);
        return layout;
    }

    private TextInputEditText input(String value) {
        TextInputEditText input = new TextInputEditText(this);
        input.setText(value);
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setTextSize(15);
        return input;
    }

    private MaterialButton button(String label) {
        MaterialButton button = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setMinHeight(dp(48));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        return button;
    }

    private TextView heading(String value, int size) {
        TextView view = text(value, getColorCompat(R.color.on_surface));
        view.setTextSize(size);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private TextView text(String value, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(14);
        view.setLineSpacing(0, 1.12f);
        view.setPadding(dp(4), dp(5), dp(4), dp(5));
        return view;
    }

    private void applyInsets(View view) {
        ViewCompat.setOnApplyWindowInsetsListener(view, (target, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            target.setPadding(Math.max(target.getPaddingLeft(), bars.left),
                    Math.max(target.getPaddingTop(), bars.top),
                    Math.max(target.getPaddingRight(), bars.right),
                    Math.max(target.getPaddingBottom(), bars.bottom));
            return insets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    private boolean isWide() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                && getResources().getConfiguration().screenWidthDp >= 600;
    }

    private String emptyAsUnknown(String value) {
        return value == null || value.trim().isEmpty() ? "未知" : value;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int getColorCompat(int id) {
        return getResources().getColor(id, getTheme());
    }

    @Override
    protected void onDestroy() {
        if (client != null) client.shutdown();
        super.onDestroy();
    }
}
