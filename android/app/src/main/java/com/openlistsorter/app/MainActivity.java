package com.openlistsorter.app;

import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import com.openlistsorter.app.data.ConnectionProfile;
import com.openlistsorter.app.data.ConnectionStore;
import com.openlistsorter.app.data.ImageConverter;
import com.openlistsorter.app.data.Models;
import com.openlistsorter.app.data.PreviewCache;
import com.openlistsorter.app.network.OpenListClient;
import com.openlistsorter.app.ui.PreviewActivity;
import com.openlistsorter.app.ui.PreviewSessionStore;
import com.openlistsorter.app.ui.RemoteDirectoryDialog;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public final class MainActivity extends AppCompatActivity {
    private static final int PAGE_SIZE = 20;
    private static final int MAX_PREVIEW_ITEMS = 200;
    private static final int MAX_PRELOAD_ITEMS = 2;
    private static final long MAX_PRELOAD_BYTES = 32L * 1024L * 1024L;
    private static final int DEFAULT_PRELOAD_CONCURRENCY = 3;
    private static final int DEFAULT_SUBMIT_CONCURRENCY = 2;
    private static final int MAIN_PREVIEW_DECODE_LIMIT = 1024;
    private static final long FULLSCREEN_RETURN_RENDER_DELAY_MS = 900L;
    private static final long DETACHED_BITMAP_RECYCLE_DELAY_MS = 500L;
    private static final int REQUEST_CREATE_DOCUMENT = 1001;
    private static final int REQUEST_PREVIEW = 1002;
    private static final String STATE_MAIN_SCREEN = "main_screen_visible";
    private static final String STATE_ENDPOINT = "connection_endpoint";
    private static final String STATE_AUTH_MODE = "connection_auth_mode";
    private static final String STATE_TOKEN = "connection_token";
    private static final String STATE_USERNAME = "connection_username";
    private static final String STATE_PASSWORD = "connection_password";
    private static final String STATE_PREVIEW_SESSION = "preview_session_id";
    private static final Object FULL_RESOLUTION_CONVERSION_LOCK = new Object();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService imageExecutor;
    private ExecutorService cacheExecutor;
    private int imageWorkerCount = 1;
    private interface ImageDecodeCallback {
        void onResult(Bitmap image, boolean cached);
    }

    private final HashMap<String, ArrayList<OpenListClient.DownloadCallback>> inFlightDownloads =
            new HashMap<>();
    private final HashMap<String, ArrayList<ImageDecodeCallback>> inFlightImageDecodes =
            new HashMap<>();
    private final HashMap<String, ArrayDeque<Runnable>> convertedUploadQueues = new HashMap<>();
    private Runnable delayedAutoLogin;
    private Runnable connectionTimeout;
    private int connectionGeneration;
    private OpenListClient client;
    private PreviewCache previewCache;
    private ConnectionProfile profile;

    private LinearLayout root;
    private LinearLayout content;
    private TextView status;
    private ProgressBar progress;
    private EditText endpointEdit;
    private EditText tokenEdit;
    private EditText usernameEdit;
    private EditText passwordEdit;
    private RadioGroup authGroup;
    private CheckBox rememberCheck;
    private CheckBox autoLoginCheck;
    private Button connectButton;
    private Button connectionExitButton;
    private CircularProgressIndicator connectionProgress;
    private int passwordModeButtonId;
    private byte[] pendingDownloadData;
    private String pendingDownloadName;
    private boolean mainScreenVisible;
    private String activePreviewSessionId;
    private boolean previewActive;
    private boolean suppressPreviewRender;
    private Runnable delayedPreviewRender;

    private String sourcePath = "/";
    private final ArrayList<Models.FileItem> files = new ArrayList<>();
    private final ArrayList<Models.TagMapping> tags = new ArrayList<>();
    private int currentIndex = -1;
    private int currentPage;
    private volatile boolean classificationMode;
    private boolean busy;
    private boolean overwrite;
    private boolean convertOnSave;
    private volatile int preloadGeneration;
    private volatile int cacheGeneration;
    private int preloadConcurrency = DEFAULT_PRELOAD_CONCURRENCY;
    private final ArrayDeque<Integer> preloadQueue = new ArrayDeque<>();
    private int preloadActiveCount;
    private int preloadCompletedCount;
    private int preloadTotalCount;
    private int submitConcurrency = DEFAULT_SUBMIT_CONCURRENCY;
    private ArrayList<Integer> submitQueue = new ArrayList<>();
    private int submitQueuePosition;
    private int submitActiveCount;
    private int submitCompletedCount;
    private long lastProgressUiUpdate;

    private TextView sourcePathView;
    private TextView visiblePathView;
    private Button downloadCurrentButton;
    private Button convertCurrentButton;
    private Button deleteCurrentButton;
    private TextView statsView;
    private TextView pageInfo;
    private Button previousPageButton;
    private Button nextPageButton;
    private RecyclerView fileList;
    private FileListAdapter fileAdapter;
    private TextView previewTitle;
    private TextView previewInfo;
    private Button previousFileButton;
    private Button nextFileButton;
    private ImageView imageView;
    private ImageView videoPlaceholder;
    private LinearLayout tagList;
    private Button enterButton;
    private Button saveButton;
    private ImageButton tagEditButton;
    private boolean tagEditMode;
    private CheckBox overwriteCheck;
    private CheckBox convertCheck;
    private LinearLayout taskBand;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // The connection form is easier to complete in portrait. Once authentication
        // succeeds, start the work area in the requested landscape orientation.
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        configureFullscreenWindow();
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        int memoryClassMb = activityManager == null ? 256 : activityManager.getMemoryClass();
        imageWorkerCount = memoryClassMb >= 512 ? 2 : 1;
        imageExecutor = Executors.newFixedThreadPool(imageWorkerCount, runnable -> {
            Thread thread = new Thread(runnable, "image-pipeline");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
        cacheExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "cache-io");
            thread.setPriority(Thread.NORM_PRIORITY - 2);
            return thread;
        });
        client = new OpenListClient();
        previewCache = new PreviewCache(this);
        PreviewSessionStore.initialize(this);
        profile = ConnectionStore.load(this);
        boolean restoreMainScreen = state != null && state.getBoolean(STATE_MAIN_SCREEN, false);
        if (state != null) activePreviewSessionId = state.getString(STATE_PREVIEW_SESSION);
        if (restoreMainScreen) {
            restoreConnectionState(state);
            mainScreenVisible = true;
            client.setBaseUrl(profile.endpoint);
            client.setToken(profile.token);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            showMainScreen();
        } else {
            showConnectionScreen();
        }
        if (!restoreMainScreen && profile.autoLogin && profile.hasCredentials()) {
            delayedAutoLogin = this::startConnection;
            mainHandler.postDelayed(delayedAutoLogin, 180);
        }
    }

    private void restoreConnectionState(Bundle state) {
        if (state == null) return;
        profile.endpoint = state.getString(STATE_ENDPOINT, profile.endpoint);
        profile.token = state.getString(STATE_TOKEN, profile.token);
        profile.username = state.getString(STATE_USERNAME, profile.username);
        profile.password = state.getString(STATE_PASSWORD, profile.password);
        String authMode = state.getString(STATE_AUTH_MODE, "token");
        profile.authMode = "password".equals(authMode)
                ? ConnectionProfile.AuthMode.PASSWORD : ConnectionProfile.AuthMode.TOKEN;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_MAIN_SCREEN, mainScreenVisible);
        if (profile != null && mainScreenVisible) {
            outState.putString(STATE_ENDPOINT, profile.endpoint);
            outState.putString(STATE_AUTH_MODE,
                    profile.authMode == ConnectionProfile.AuthMode.PASSWORD ? "password" : "token");
            outState.putString(STATE_TOKEN, profile.token);
            outState.putString(STATE_USERNAME, profile.username);
            outState.putString(STATE_PASSWORD, profile.password);
        }
        if (activePreviewSessionId != null) {
            outState.putString(STATE_PREVIEW_SESSION, activePreviewSessionId);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideStatusBar();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mainScreenVisible) {
            showMainScreen();
        } else {
            showConnectionScreen();
        }
    }

    private void configureFullscreenWindow() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.statusBars());
    }

    private void hideStatusBar() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .hide(WindowInsetsCompat.Type.statusBars());
    }

    @Override
    protected void onDestroy() {
        ++preloadGeneration;
        ++connectionGeneration;
        delayedAutoLogin = null;
        connectionTimeout = null;
        delayedPreviewRender = null;
        mainHandler.removeCallbacksAndMessages(null);
        inFlightDownloads.clear();
        inFlightImageDecodes.clear();
        convertedUploadQueues.clear();
        if (imageExecutor != null) imageExecutor.shutdownNow();
        if (cacheExecutor != null) cacheExecutor.shutdownNow();
        client.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PREVIEW) {
            previewActive = false;
            schedulePreviewRenderAfterFullscreen();
            boolean consumed = false;
            if (resultCode == RESULT_OK && data != null) {
                try {
                    String sessionId = data.getStringExtra(PreviewActivity.EXTRA_SESSION_ID);
                    PreviewSessionStore.Session session = PreviewSessionStore.peek(sessionId);
                    if (session != null) {
                        mergePreviewResult(session.items, session.currentPath());
                        consumed = true;
                    } else {
                        ArrayList<Models.PreviewItem> updated = data.getParcelableArrayListExtra(
                                PreviewActivity.EXTRA_RESULT_ITEMS);
                        String selectedPath = data.getStringExtra(PreviewActivity.EXTRA_RESULT_PATH);
                        if (updated != null) {
                            mergePreviewResult(updated, selectedPath);
                            consumed = true;
                        }
                    }
                } catch (RuntimeException | OutOfMemoryError error) {
                    // Returning from a large preview must never take down the activity. The
                    // lightweight preview session remains available for the next render/reload.
                    if (status != null) status.setText("全屏结果已保留，正在恢复列表");
                }
            }
            if (consumed) PreviewSessionStore.discard(activePreviewSessionId);
            activePreviewSessionId = null;
            return;
        }
        if (requestCode != REQUEST_CREATE_DOCUMENT) {
            return;
        }
        byte[] bytes = pendingDownloadData;
        String name = pendingDownloadName;
        pendingDownloadData = null;
        pendingDownloadName = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || bytes == null) {
            if (status != null) status.setText("已取消保存");
            return;
        }
        Uri target = data.getData();
        try {
            cacheExecutor.execute(() -> {
                String errorMessage = "";
                try (OutputStream output = getContentResolver().openOutputStream(target, "w")) {
                    if (output == null) {
                        errorMessage = "无法打开用户选择的保存位置。";
                    } else {
                        output.write(bytes);
                        output.flush();
                    }
                } catch (IOException error) {
                    errorMessage = "下载保存失败: " + error.getMessage();
                }
                String finalError = errorMessage;
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (finalError.isEmpty()) showInfo("已下载: " + name);
                    else showError(finalError);
                });
            });
        } catch (RejectedExecutionException error) {
            showError("后台写入任务已停止，请重新下载。");
        }
    }

    private void mergePreviewResult(ArrayList<Models.PreviewItem> updated, String selectedPath) {
        if (updated == null || updated.isEmpty()) return;
        if (files.isEmpty()) {
            for (Models.PreviewItem preview : updated) {
                if (preview.removed) continue;
                Models.FileItem restored = new Models.FileItem(preview.toEntry());
                restored.submitted = !preview.tagsEditable;
                files.add(restored);
            }
            classificationMode = true;
            updateClassificationButton();
        }
        HashMap<String, Models.PreviewItem> byPath = new HashMap<>();
        for (Models.PreviewItem preview : updated) byPath.put(preview.path, preview);
        for (int i = files.size() - 1; i >= 0; i--) {
            Models.PreviewItem preview = byPath.get(files.get(i).entry.path);
            if (preview != null && preview.removed) files.remove(i);
        }
        for (Models.FileItem item : files) {
            Models.PreviewItem preview = byPath.get(item.entry.path);
            if (preview == null || preview.removed || item.submitted) continue;
            item.selectedTags.clear();
            item.selectedTags.addAll(preview.selectedTags);
            if (preview.rawUrl != null && !preview.rawUrl.isEmpty()) {
                item.entry.rawUrl = preview.rawUrl;
            }
        }
        // The process may have been reclaimed while the preview Activity was visible. In that
        // case the result is still harmless, but the old view hierarchy is no longer available.
        // Keep the result path in the session store and wait for the user to reload the folder
        // instead of dereferencing stale/null views during onActivityResult.
        if (!mainScreenVisible || previewTitle == null || previewInfo == null) return;
        int selectedIndex = -1;
        if (selectedPath != null && !selectedPath.isEmpty()) {
            for (int i = 0; i < files.size(); i++) {
                if (selectedPath.equals(files.get(i).entry.path)) {
                    selectedIndex = i;
                    break;
                }
            }
        }
        if (selectedIndex >= 0) {
            setCurrentIndex(selectedIndex);
        } else {
            renderFileList();
            refreshTags();
            updateStats();
            renderCurrentFile();
        }
    }

    private void showConnectionScreen() {
        mainScreenVisible = false;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        root = baseRoot();
        applySystemBarInsets(root);

        LinearLayout appBar = topBand();
        TextView title = heading("OpenList Sorter", 20);
        title.setTextColor(getColorCompat(R.color.primary));
        TextView subtitle = text("连接 OpenList / AList", 0xff505a59);
        subtitle.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout brandRow = row();
        brandRow.addView(title, new LinearLayout.LayoutParams(-2, -2));
        brandRow.addView(subtitle, new LinearLayout.LayoutParams(0, -2, 1));
        appBar.addView(brandRow);
        applyCutoutInsets(appBar);
        root.addView(appBar, new LinearLayout.LayoutParams(-1, -2));

        MaterialPanel card = panel();

        endpointEdit = input("https://openlist.example.com");
        card.addView(labelled("服务器地址（Endpoint）", endpointEdit));

        authGroup = new RadioGroup(this);
        authGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton tokenMode = radio("访问令牌");
        RadioButton passwordMode = radio("账号密码");
        tokenMode.setId(View.generateViewId());
        passwordMode.setId(View.generateViewId());
        passwordModeButtonId = passwordMode.getId();
        authGroup.addView(tokenMode);
        authGroup.addView(passwordMode);
        tokenMode.setChecked(true);
        card.addView(authGroup);

        tokenEdit = input("访问令牌");
        tokenEdit.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(labelled("访问令牌", tokenEdit));
        LinearLayout passwordFields = new LinearLayout(this);
        passwordFields.setOrientation(LinearLayout.VERTICAL);
        usernameEdit = input("用户名");
        passwordEdit = input("密码");
        passwordEdit.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordFields.addView(labelled("用户名", usernameEdit));
        passwordFields.addView(labelled("密码", passwordEdit));
        card.addView(passwordFields);
        passwordFields.setVisibility(View.GONE);

        authGroup.setOnCheckedChangeListener((group, checkedId) -> {
            boolean password = checkedId == passwordMode.getId();
            tokenEdit.setVisibility(password ? View.GONE : View.VISIBLE);
            passwordFields.setVisibility(password ? View.VISIBLE : View.GONE);
        });

        rememberCheck = new MaterialCheckBox(this);
        rememberCheck.setText("记住我");
        autoLoginCheck = new MaterialCheckBox(this);
        autoLoginCheck.setText("自动登录");
        LinearLayout checks = row();
        checks.addView(rememberCheck);
        checks.addView(autoLoginCheck);
        card.addView(checks);
        rememberCheck.setChecked(profile.rememberMe);
        autoLoginCheck.setChecked(profile.autoLogin && profile.rememberMe);
        autoLoginCheck.setEnabled(profile.rememberMe);
        rememberCheck.setOnCheckedChangeListener((button, checked) -> {
            autoLoginCheck.setEnabled(checked);
            if (!checked) autoLoginCheck.setChecked(false);
        });

        status = text(" ", 0xff505a59);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setTextSize(13);
        card.addView(status);
        connectionProgress = new CircularProgressIndicator(this);
        connectionProgress.setIndeterminate(true);
        connectionProgress.setVisibility(View.GONE);
        connectionProgress.setContentDescription("正在连接");
        card.addView(connectionProgress, new LinearLayout.LayoutParams(dp(32), dp(32)));
        connectButton = primaryButton("连接");
        connectionExitButton = button("退出");
        LinearLayout actions = row();
        actions.setGravity(Gravity.END);
        actions.addView(connectionExitButton);
        actions.addView(connectButton);
        card.addView(actions);
        connectButton.setOnClickListener(view -> startConnection());
        connectionExitButton.setOnClickListener(view -> {
            if (busy) {
                cancelConnection();
            } else {
                finish();
            }
        });

        endpointEdit.setText(profile.endpoint);
        tokenEdit.setText(profile.token);
        usernameEdit.setText(profile.username);
        passwordEdit.setText(profile.password);
        if (profile.authMode == ConnectionProfile.AuthMode.PASSWORD) {
            passwordMode.setChecked(true);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setGravity(Gravity.CENTER_HORIZONTAL);
        wrapper.setPadding(dp(16), dp(2), dp(16), dp(8));
        wrapper.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        scroll.addView(wrapper, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        ViewCompat.requestApplyInsets(root);
    }

    private void startConnection() {
        if (busy) return;
        ConnectionProfile requested = readProfile();
        if (requested.endpoint.isEmpty()) {
            showError("请输入 endpoint");
            return;
        }
        if (isLoopbackEndpoint(requested.endpoint)) {
            showError("Android 上的 localhost/127.0.0.1 指向手机本身。请填写运行 OpenList 电脑的局域网 IP，例如 http://192.168.1.5:5244。");
            return;
        }
        if (!requested.hasCredentials()) {
            showError("请输入访问令牌，或完整的用户名和密码");
            return;
        }
        final int attempt = ++connectionGeneration;
        busy = true;
        connectButton.setEnabled(false);
        connectionExitButton.setText("取消");
        connectionExitButton.setEnabled(true);
        connectionProgress.setVisibility(View.VISIBLE);
        status.setText("正在连接...");
        connectionTimeout = () -> {
            if (attempt != connectionGeneration || !busy) return;
            ++connectionGeneration;
            busy = false;
            connectButton.setEnabled(true);
            connectionExitButton.setText("退出");
            connectionExitButton.setEnabled(true);
            connectionProgress.setVisibility(View.GONE);
            status.setText("连接超时，正在检测原因...");
            client.shutdown();
            client = new OpenListClient();
            client.setBaseUrl(requested.endpoint);
            final int diagnosticGeneration = connectionGeneration;
            client.fetchConnectionDiagnostics(diagnostics -> {
                if (diagnosticGeneration != connectionGeneration || busy) return;
                String cause = diagnostics.publicSettingsOk
                        ? "服务器可达，但认证请求未在限时内完成（可能是服务器响应慢或鉴权方式不匹配）"
                        : (diagnostics.message == null || diagnostics.message.isEmpty()
                                ? "无法连接到服务器（无响应）" : diagnostics.message);
                status.setText("连接超时（20 秒）。检测结果: " + cause
                        + "\n请检查地址、端口、网络与 HTTPS 证书后重试。");
            });
        };
        mainHandler.postDelayed(connectionTimeout, 20000L);
        OpenListClient.StageCallback stage = message -> {
            if (attempt == connectionGeneration && busy) status.setText(message);
        };
        OpenListClient.SimpleCallback callback = (ok, message) -> {
            if (attempt != connectionGeneration) return;
            if (connectionTimeout != null) mainHandler.removeCallbacks(connectionTimeout);
            connectionTimeout = null;
            busy = false;
            connectButton.setEnabled(true);
            connectionExitButton.setText("退出");
            connectionExitButton.setEnabled(true);
            connectionProgress.setVisibility(View.GONE);
            if (!ok) {
                status.setText("连接失败: " + message);
                return;
            }
            profile = requested;
            if (profile.rememberMe) {
                ConnectionStore.save(this, profile);
            } else {
                ConnectionStore.clearRememberedConnection(this);
            }
            mainScreenVisible = true;
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            showMainScreen();
        };
        if (requested.authMode == ConnectionProfile.AuthMode.PASSWORD) {
            client.login(requested.endpoint, requested.username, requested.password, stage, callback);
        } else {
            client.validateToken(requested.endpoint, requested.token, stage, callback);
        }
    }

    private boolean isLoopbackEndpoint(String endpoint) {
        try {
            String normalized = endpoint.trim();
            if (!normalized.contains("://")) normalized = "http://" + normalized;
            String host = java.net.URI.create(normalized).getHost();
            return host != null && (host.equalsIgnoreCase("localhost")
                    || host.equals("127.0.0.1") || host.equals("0.0.0.0")
                    || host.equals("::1"));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private void cancelConnection() {
        ++connectionGeneration;
        if (connectionTimeout != null) {
            mainHandler.removeCallbacks(connectionTimeout);
            connectionTimeout = null;
        }
        client.shutdown();
        client = new OpenListClient();
        busy = false;
        connectButton.setEnabled(true);
        connectionExitButton.setText("退出");
        connectionExitButton.setEnabled(true);
        connectionProgress.setVisibility(View.GONE);
        status.setText("已取消连接");
    }

    private ConnectionProfile readProfile() {
        ConnectionProfile result = new ConnectionProfile();
        result.endpoint = endpointEdit.getText().toString().trim();
        boolean password = authGroup.getCheckedRadioButtonId() == passwordModeButtonId;
        result.authMode = password ? ConnectionProfile.AuthMode.PASSWORD : ConnectionProfile.AuthMode.TOKEN;
        result.token = tokenEdit.getText().toString().trim();
        result.username = usernameEdit.getText().toString().trim();
        result.password = passwordEdit.getText().toString();
        result.rememberMe = rememberCheck.isChecked();
        result.autoLogin = autoLoginCheck.isChecked() && result.rememberMe;
        return result;
    }

    private void showMainScreen() {
        mainScreenVisible = true;
        root = baseRoot();
        applySystemBarInsets(root);

        enterButton = iconButton(android.R.drawable.ic_media_play, "进入分类模式");
        visiblePathView = text(sourcePath, getColorCompat(R.color.on_surface));
        visiblePathView.setTextSize(14);
        visiblePathView.setSingleLine(true);
        visiblePathView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        visiblePathView.setGravity(Gravity.CENTER_VERTICAL);
        visiblePathView.setPadding(dp(4), 0, dp(4), dp(2));
        visiblePathView.setClickable(true);
        visiblePathView.setFocusable(true);
        visiblePathView.setContentDescription("当前源目录，点击选择目录");

        LinearLayout appBar = topBand();
        LinearLayout titleRow = row();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        status = text("", 0xff505a59);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setTextSize(11);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setVisibility(View.GONE);
        status.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                status.setVisibility(value == null || value.length() == 0 ? View.GONE : View.VISIBLE);
            }
            @Override public void afterTextChanged(android.text.Editable value) {}
        });
        LinearLayout pathBlock = new LinearLayout(this);
        pathBlock.setOrientation(LinearLayout.VERTICAL);
        TextView locationLabel = text("源目录", getColorCompat(R.color.on_surface_variant));
        locationLabel.setTextSize(11);
        locationLabel.setPadding(dp(4), dp(2), dp(4), 0);
        pathBlock.addView(locationLabel, new LinearLayout.LayoutParams(-1, dp(18)));
        pathBlock.addView(visiblePathView, new LinearLayout.LayoutParams(-1, dp(30)));
        titleRow.addView(enterButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        titleRow.addView(pathBlock, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button more = iconButton(R.drawable.ic_more_vertical, "设置与更多操作");
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        moreParams.setMargins(0, 0, dp(8), 0);
        saveButton = iconButton(android.R.drawable.ic_menu_upload, "提交已选分类");
        titleRow.addView(saveButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        titleRow.addView(more, moreParams);
        appBar.addView(titleRow);
        appBar.addView(status, new LinearLayout.LayoutParams(-1, -2));
        root.addView(appBar, new LinearLayout.LayoutParams(-1, -2));
        more.setOnClickListener(view -> showMoreActions());

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(12));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        progress = new LinearProgressIndicator(this);
        progress.setContentDescription("当前操作进度");
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        content.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));

        taskBand = new LinearLayout(this);
        taskBand.setOrientation(LinearLayout.VERTICAL);
        taskBand.setBackgroundColor(getColorCompat(R.color.surface));
        taskBand.setPadding(dp(12), dp(6), dp(12), dp(6));
        boolean wideWorkspace = isWideWorkspace();
        boolean portrait = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT;
        LinearLayout sourceRow = row();
        sourceRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView taskLabel = text("源目录", getColorCompat(R.color.on_surface_variant));
        taskLabel.setTextSize(12);
        taskLabel.setPadding(0, 0, dp(12), 0);
        sourceRow.addView(taskLabel, new LinearLayout.LayoutParams(-2, -2));
        sourcePathView = text(sourcePath, Color.DKGRAY);
        sourcePathView.setTypeface(Typeface.MONOSPACE);
        sourcePathView.setTextSize(13);
        sourcePathView.setSingleLine(true);
        sourcePathView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        sourcePathView.setGravity(Gravity.CENTER_VERTICAL);
        sourcePathView.setClickable(true);
        sourcePathView.setFocusable(true);
        sourcePathView.setContentDescription("分类源目录，点击选择目录");
        sourceRow.addView(sourcePathView, new LinearLayout.LayoutParams(0, dp(48), 1));
        if (portrait) {
            sourceRow.removeAllViews();
            sourceRow.addView(taskLabel, new LinearLayout.LayoutParams(-1, -2));
            sourceRow.addView(sourcePathView, new LinearLayout.LayoutParams(-1, dp(48)));
            sourceRow.setOrientation(LinearLayout.VERTICAL);
            sourceRow.setGravity(Gravity.START);
        }
        taskBand.addView(sourceRow);

        LinearLayout taskActions = row();
        LinearLayout optionRow = taskActions;
        LinearLayout commandRow = taskActions;
        if (portrait) {
            taskActions.setOrientation(LinearLayout.VERTICAL);
            optionRow = row();
            commandRow = row();
            commandRow.setGravity(Gravity.END);
            taskActions.addView(optionRow, new LinearLayout.LayoutParams(-1, -2));
            taskActions.addView(commandRow, new LinearLayout.LayoutParams(-1, -2));
        }
        overwriteCheck = new MaterialCheckBox(this);
        overwriteCheck.setText("覆盖同名文件");
        convertCheck = new MaterialCheckBox(this);
        convertCheck.setText("保存时转 WebP");
        optionRow.addView(overwriteCheck);
        optionRow.addView(convertCheck);
        downloadCurrentButton = iconButton(android.R.drawable.ic_menu_save, "下载当前原始文件");
        convertCurrentButton = iconButton(android.R.drawable.ic_menu_manage, "仅转换当前图片为 WebP");
        deleteCurrentButton = iconButton(android.R.drawable.ic_menu_delete, "删除当前远程文件");
        deleteCurrentButton.setTextColor(0xffb3261e);
        if (deleteCurrentButton instanceof MaterialButton) {
            MaterialButton danger = (MaterialButton) deleteCurrentButton;
            danger.setIconTint(ColorStateList.valueOf(0xffb3261e));
        }
        taskBand.addView(taskActions);

        MaterialPanel filesPanel = panel();
        LinearLayout filesHeader = new LinearLayout(this);
        filesHeader.setOrientation(LinearLayout.VERTICAL);
        filesHeader.setPadding(0, 0, 0, dp(4));
        TextView filesHeading = heading("当前目录文件", 16);
        statsView = text("总文件 0 | 已提交 0 | 待保存 0 | 未处理 0", 0xff505a59);
        statsView.setTextSize(11);
        statsView.setSingleLine(false);
        statsView.setMaxLines(2);
        statsView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statsView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        statsView.setPadding(0, 0, 0, dp(2));
        filesHeader.addView(filesHeading, new LinearLayout.LayoutParams(-1, -2));
        filesHeader.addView(statsView, new LinearLayout.LayoutParams(-1, -2));
        filesPanel.addView(filesHeader);
        LinearLayout pageRow = row();
        previousPageButton = iconButton(android.R.drawable.ic_media_previous, "上一页");
        previousPageButton.setContentDescription("上一页");
        nextPageButton = iconButton(android.R.drawable.ic_media_next, "下一页");
        nextPageButton.setContentDescription("下一页");
        pageInfo = text("0 / 0", 0xff505a59);
        pageInfo.setGravity(Gravity.CENTER);
        pageInfo.setMinWidth(dp(40));
        pageRow.addView(previousPageButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        pageRow.addView(pageInfo, new LinearLayout.LayoutParams(0, dp(48), 1));
        pageRow.addView(nextPageButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        filesPanel.addView(pageRow);
        fileList = new RecyclerView(this);
        fileList.setLayoutManager(new LinearLayoutManager(this));
        fileList.setNestedScrollingEnabled(true);
        fileAdapter = new FileListAdapter();
        fileList.setAdapter(fileAdapter);
        filesPanel.addView(fileList, new LinearLayout.LayoutParams(-1, 0, 1));
        fileList.setBackgroundResource(R.drawable.preview_background);
        fileList.setPadding(dp(2), dp(2), dp(2), dp(2));

        MaterialPanel previewPanel = panel();
        previewTitle = heading("当前文件", 16);
        previewTitle.setSingleLine(true);
        previewTitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        previousFileButton = iconButton(android.R.drawable.ic_media_previous, "上一个");
        nextFileButton = iconButton(android.R.drawable.ic_media_next, "下一个");
        // Keep the semantic current-file node for accessibility and state restoration, while the
        // visible preview intentionally has no filename header.
        previewTitle.setVisibility(View.GONE);
        previewPanel.addView(previewTitle, new LinearLayout.LayoutParams(0, 0));
        previewInfo = text("请选择文件", 0xff505a59);
        previewInfo.setBackgroundResource(R.drawable.preview_background);
        previewInfo.setPadding(dp(10), dp(8), dp(10), dp(8));
        previewInfo.setTextSize(12);
        previewInfo.setMaxLines(4);
        previewInfo.setEllipsize(android.text.TextUtils.TruncateAt.END);
        previewInfo.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        previewPanel.addView(previewInfo, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout previewImageHost = new FrameLayout(this);
        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setBackgroundResource(R.drawable.preview_background);
        imageView.setContentDescription("当前图片预览，点按全屏查看");
        previewImageHost.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        videoPlaceholder = new ImageView(this);
        videoPlaceholder.setImageResource(android.R.drawable.ic_media_play);
        videoPlaceholder.setContentDescription("在外部播放器中打开视频");
        videoPlaceholder.setVisibility(View.GONE);
        previewImageHost.addView(videoPlaceholder, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        previewImageHost.setContentDescription("当前文件预览区域，点按全屏查看");
        previewImageHost.setOnClickListener(view -> openPreview());
        previewPanel.addView(previewImageHost, new LinearLayout.LayoutParams(-1, 0, 1));

        MaterialPanel tagsPanel = panel();
        tagsPanel.setInnerPadding(dp(14), dp(6), dp(14), dp(6));
        LinearLayout tagActions = row();
        tagActions.setGravity(Gravity.CENTER_VERTICAL);
        tagActions.addView(heading("分类标签", 16), new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton addTag = compactIconButton(R.drawable.ic_tag_add, "新增标签");
        tagActions.addView(addTag, new LinearLayout.LayoutParams(dp(40), dp(40)));
        tagEditButton = compactIconButton(R.drawable.ic_tag_edit, "编辑标签");
        tagEditButton.setContentDescription(tagEditMode ? "关闭标签编辑" : "编辑标签");
        tagActions.addView(tagEditButton, new LinearLayout.LayoutParams(dp(40), dp(40)));
        tagsPanel.addView(tagActions);
        ScrollView tagsScroll = new ScrollView(this);
        tagsScroll.setFillViewport(true);
        tagList = new LinearLayout(this);
        tagList.setOrientation(LinearLayout.VERTICAL);
        tagsScroll.addView(tagList, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        tagsPanel.addView(tagsScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        filesPanel.fillHeight();
        previewPanel.fillHeight();
        tagsPanel.fillHeight();
        addWorkspacePanels(filesPanel, previewPanel, tagsPanel);

        sourcePathView.setOnClickListener(view -> pickDirectory(sourcePath, "lastSourceDirectory", selected -> {
            sourcePath = selected;
            sourcePathView.setText(sourcePath);
            visiblePathView.setText(sourcePath);
            saveLocalSettings();
        }));
        visiblePathView.setOnClickListener(view -> pickDirectory(sourcePath, "lastSourceDirectory", selected -> {
            sourcePath = selected;
            sourcePathView.setText(sourcePath);
            visiblePathView.setText(sourcePath);
            saveLocalSettings();
        }));
        enterButton.setOnClickListener(view -> {
            if (classificationMode) exitClassificationMode(); else enterClassificationMode();
        });
        previousPageButton.setOnClickListener(view -> setPage(currentPage - 1));
        nextPageButton.setOnClickListener(view -> setPage(currentPage + 1));
        previousFileButton.setOnClickListener(view -> setCurrentIndex(currentIndex - 1));
        nextFileButton.setOnClickListener(view -> setCurrentIndex(currentIndex + 1));
        addTag.setOnClickListener(view -> addTag());
        tagEditButton.setOnClickListener(view -> setTagEditMode(!tagEditMode));
        saveButton.setOnClickListener(view -> savePending());
        downloadCurrentButton.setOnClickListener(view -> downloadCurrent());
        convertCurrentButton.setOnClickListener(view -> convertCurrent());
        deleteCurrentButton.setOnClickListener(view -> deleteCurrent());
        loadLocalSettings();
        overwriteCheck.setOnCheckedChangeListener((button, checked) -> {
            overwrite = checked;
            saveLocalSettings();
        });
        convertCheck.setOnCheckedChangeListener((button, checked) -> {
            convertOnSave = checked;
            saveLocalSettings();
        });
        refreshTags();
        updateStats();
        taskBand.setVisibility(View.GONE);
        setContentView(root);
        ViewCompat.requestApplyInsets(root);
        if (classificationMode) {
            renderFileList();
            renderCurrentFile();
            refreshTags();
            updateStats();
        }
        updateClassificationButton();
        enterButton.setEnabled(!busy);
    }

    private void showTaskSettings() {
        if (busy) {
            showError("当前有任务正在进行，请等待完成");
            return;
        }
        if (taskBand.getParent() instanceof ViewGroup) {
            ((ViewGroup) taskBand.getParent()).removeView(taskBand);
        }
        taskBand.setVisibility(View.VISIBLE);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("分类任务设置")
                .setView(taskBand)
                .setPositiveButton("完成", (ignored, which) -> restoreTaskBand())
                .create();
        dialog.setOnDismissListener(ignored -> restoreTaskBand());
        dialog.show();
    }

    private void restoreTaskBand() {
        if (taskBand == null || taskBand.getParent() != null) return;
        content.addView(taskBand, 0, new LinearLayout.LayoutParams(-1, -2));
        taskBand.setVisibility(View.GONE);
    }

    private void addWorkspacePanels(MaterialPanel filesPanel,
                                    MaterialPanel previewPanel, MaterialPanel tagsPanel) {
        LinearLayout workspace = new LinearLayout(this);
        boolean wide = isWideWorkspace();
        workspace.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        workspace.setGravity(Gravity.TOP);
        if (wide) {
            workspace.addView(filesPanel, weightedWorkspaceParams(0.29f, 0, dp(4)));
            workspace.addView(previewPanel, weightedWorkspaceParams(0.42f, dp(4), dp(4)));
            workspace.addView(tagsPanel, weightedWorkspaceParams(0.29f, dp(4), 0));
            content.addView(workspace, new LinearLayout.LayoutParams(-1, 0, 1));
            return;
        }

        // On phones the desktop three-column layout becomes a single, readable flow.
        // Give each workspace section a useful viewport instead of letting weighted
        // children collapse while the parent is measured by a ScrollView.
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout.LayoutParams filesParams = mobileWorkspaceParams(dp(264), 0, dp(8));
        LinearLayout.LayoutParams previewParams = mobileWorkspaceParams(dp(340), 0, dp(8));
        LinearLayout.LayoutParams tagsParams = mobileWorkspaceParams(dp(320), 0, dp(8));
        workspace.addView(filesPanel, filesParams);
        workspace.addView(previewPanel, previewParams);
        workspace.addView(tagsPanel, tagsParams);
        scroll.addView(workspace, new ScrollView.LayoutParams(-1, -2));
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private LinearLayout.LayoutParams mobileWorkspaceParams(int height, int left, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, height);
        params.setMargins(left, 0, left, bottom);
        return params;
    }

    private boolean isWideWorkspace() {
        return getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE
                && getResources().getConfiguration().screenWidthDp >= 600;
    }

    private LinearLayout.LayoutParams weightedWorkspaceParams(float weight, int left, int right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, weight);
        params.setMargins(left, 0, right, 0);
        return params;
    }

    private void loadLocalSettings() {
        android.content.SharedPreferences prefs = getSharedPreferences("task", MODE_PRIVATE);
        sourcePath = Models.normalizeRemotePath(prefs.getString("sourcePath", sourcePath));
        sourcePathView.setText(sourcePath);
        if (visiblePathView != null) visiblePathView.setText(sourcePath);
        overwrite = prefs.getBoolean("overwrite", false);
        convertOnSave = prefs.getBoolean("convertOnSave", false);
        preloadConcurrency = Math.max(1, Math.min(10,
                prefs.getInt("preloadConcurrency", DEFAULT_PRELOAD_CONCURRENCY)));
        submitConcurrency = Math.max(1, Math.min(4,
                prefs.getInt("submitConcurrency", DEFAULT_SUBMIT_CONCURRENCY)));
        overwriteCheck.setChecked(overwrite);
        convertCheck.setChecked(convertOnSave);
        tags.clear();
        int count = prefs.getInt("tagCount", 0);
        for (int i = 0; i < count; i++) {
            String name = prefs.getString("tagName" + i, "");
            String path = prefs.getString("tagPath" + i, "/");
            if (!name.isEmpty()) tags.add(new Models.TagMapping(name, Models.normalizeRemotePath(path)));
        }
    }

    private void saveLocalSettings() {
        android.content.SharedPreferences.Editor editor = getSharedPreferences("task", MODE_PRIVATE).edit();
        editor.putString("sourcePath", sourcePath).putBoolean("overwrite", overwrite)
                .putBoolean("convertOnSave", convertOnSave)
                .putInt("preloadConcurrency", preloadConcurrency)
                .putInt("submitConcurrency", submitConcurrency)
                .putInt("tagCount", tags.size());
        for (int i = 0; i < tags.size(); i++) {
            editor.putString("tagName" + i, tags.get(i).name).putString("tagPath" + i, tags.get(i).targetPath);
        }
        editor.apply();
    }

    private void pickDirectory(String startPath, String historyKey,
                               RemoteDirectoryDialog.SelectionCallback callback) {
        android.content.SharedPreferences prefs = getSharedPreferences("task", MODE_PRIVATE);
        String fallback = Models.normalizeRemotePath(startPath);
        String remembered = Models.normalizeRemotePath(prefs.getString(historyKey, fallback));
        new RemoteDirectoryDialog(this, client, remembered, selected -> {
            String normalized = Models.normalizeRemotePath(selected);
            prefs.edit().putString(historyKey, normalized).apply();
            callback.onSelected(normalized);
        }).show();
    }

    private void enterClassificationMode() {
        if (!client.hasConnection()) {
            showError("请先连接 OpenList");
            return;
        }
        classificationMode = true;
        updateClassificationButton();
        files.clear();
        currentIndex = -1;
        updateStats();
        setBusy(true, "正在读取源目录: " + sourcePath);
        client.listDirectory(sourcePath, false, (ok, message, path, entries) -> {
            setBusy(false, "");
            if (!ok) {
                classificationMode = false;
                updateClassificationButton();
                showError(message);
                return;
            }
            for (Models.RemoteEntry entry : entries) {
                if (!entry.isDir) files.add(new Models.FileItem(entry));
            }
            if (files.isEmpty()) {
                showInfo("当前目录没有可分类文件");
                renderFileList();
                return;
            }
            setCurrentIndex(0);
            preloadCurrentPage();
        });
    }

    private void exitClassificationMode() {
        if (busy) return;
        int pending = pendingCount();
        if (pending > 0) {
            new MaterialAlertDialogBuilder(this).setTitle("退出分类模式")
                    .setMessage("当前有 " + pending + " 个已选择但未保存的文件，退出会放弃选择。继续吗？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("退出", (dialog, which) -> finishClassificationMode()).show();
        } else {
            finishClassificationMode();
        }
    }

    private void finishClassificationMode() {
        if (busy) return;
        ++preloadGeneration;
        classificationMode = false;
        tagEditMode = false;
        updateClassificationButton();
        files.clear();
        currentIndex = -1;
        currentPage = 0;
        renderFileList();
        refreshTags();
        updateStats();
        renderCurrentFile();
        showInfo("已退出分类模式");
    }

    private void setCurrentIndex(int index) {
        if (index < 0 || index >= files.size()) return;
        currentIndex = index;
        int targetPage = index / PAGE_SIZE;
        boolean pageChanged = targetPage != currentPage;
        if (pageChanged) currentPage = targetPage;
        renderFileList();
        refreshTags();
        updateStats();
        renderCurrentFile();
        if (pageChanged) preloadCurrentPage();
    }

    private void setPage(int page) {
        int count = pageCount();
        if (count == 0) return;
        currentPage = Math.max(0, Math.min(page, count - 1));
        int start = currentPage * PAGE_SIZE;
        if (currentIndex < start || currentIndex >= pageEnd()) currentIndex = start;
        renderFileList();
        renderCurrentFile();
        refreshTags();
        updateStats();
        preloadCurrentPage();
    }

    private void preloadCurrentPage() {
        final int generation = ++preloadGeneration;
        preloadQueue.clear();
        preloadActiveCount = 0;
        preloadCompletedCount = 0;
        for (int index = currentPage * PAGE_SIZE; index < pageEnd()
                && preloadQueue.size() < MAX_PRELOAD_ITEMS; index++) {
            Models.RemoteEntry entry = files.get(index).entry;
            if (Models.isImageFileName(entry.name) && entry.size <= MAX_PRELOAD_BYTES) {
                preloadQueue.add(index);
            }
        }
        preloadTotalCount = preloadQueue.size();
        if (!classificationMode || busy || preloadTotalCount == 0) return;
        if (progress != null) {
            progress.setIndeterminate(false);
            progress.setMax(preloadTotalCount);
            progress.setProgress(0);
            progress.setVisibility(View.VISIBLE);
        }
        if (status != null) status.setText("正在并发预热当前页: 0 / " + preloadTotalCount);
        startPreloadWorkers(generation);
    }

    private void startPreloadWorkers(int generation) {
        if (generation != preloadGeneration || !classificationMode || busy) return;
        while (preloadActiveCount < preloadConcurrency && !preloadQueue.isEmpty()) {
            int index = preloadQueue.removeFirst();
            preloadActiveCount++;
            preloadItem(generation, index);
        }
        if (preloadActiveCount == 0 && preloadQueue.isEmpty()) {
            if (progress != null) progress.setVisibility(View.GONE);
            if (status != null) status.setText("当前页缓存已准备: " + preloadCompletedCount
                    + " / " + preloadTotalCount);
        }
    }

    private void preloadItem(int generation, int index) {
        if (index < 0 || index >= files.size()) {
            finishPreloadItem(generation, "已跳过");
            return;
        }
        Models.FileItem item = files.get(index);
        try {
            cacheExecutor.execute(() -> {
                boolean imageCached = previewCache.hasImage(item.entry);
                boolean rawCached = previewCache.hasRawData(item.entry);
                mainHandler.post(() -> {
                    if (generation != preloadGeneration || !classificationMode) return;
                    if (imageCached || rawCached) {
                        finishPreloadItem(generation, ImageConverter.isHeifLike(null, item.entry.name)
                                ? "HEIF 原图已缓存" : "缓存命中");
                    } else {
                        fetchFileData(item, (ok, message, data) -> {
                            if (generation != preloadGeneration || !classificationMode) return;
                            finishPreloadItem(generation, ok
                                    ? (ImageConverter.isHeifLike(data, item.entry.name)
                                    ? "HEIF 原图已缓存" : "原图已缓存") : "读取失败");
                        }, "正在预热 " + item.entry.name, true);
                    }
                });
            });
        } catch (RejectedExecutionException error) {
            finishPreloadItem(generation, "任务已停止");
        }
    }

    private void finishPreloadItem(int generation, String detail) {
        if (generation != preloadGeneration || !classificationMode) return;
        preloadActiveCount = Math.max(0, preloadActiveCount - 1);
        preloadCompletedCount++;
        if (progress != null) {
            progress.setIndeterminate(false);
            progress.setMax(Math.max(1, preloadTotalCount));
            progress.setProgress(preloadCompletedCount);
        }
        if (status != null) status.setText("并发预热: " + preloadCompletedCount + " / "
                + preloadTotalCount + " · " + detail);
        startPreloadWorkers(generation);
    }

    private int pageCount() {
        return files.isEmpty() ? 0 : (files.size() + PAGE_SIZE - 1) / PAGE_SIZE;
    }

    private int pageEnd() {
        return Math.min(files.size(), currentPage * PAGE_SIZE + PAGE_SIZE);
    }

    private void renderFileList() {
        if (fileList == null || fileAdapter == null) return;
        fileAdapter.notifyDataSetChanged();
        int start = currentPage * PAGE_SIZE;
        int end = pageEnd();
        int pages = pageCount();
        if (pages == 0) {
            pageInfo.setText("0 / 0");
        } else {
            pageInfo.setText((currentPage + 1) + " / " + pages + "  "
                    + (start + 1) + "-" + end + " / " + files.size());
        }
        previousPageButton.setEnabled(classificationMode && !busy && currentPage > 0);
        nextPageButton.setEnabled(classificationMode && !busy && pages > 0 && currentPage < pages - 1);
    }

    private void renderCurrentFile() {
        if (previewTitle == null || previewInfo == null || imageView == null
                || videoPlaceholder == null) return;
        if (currentIndex < 0 || currentIndex >= files.size()) {
            previewTitle.setText("预览");
            previewInfo.setText(classificationMode ? "当前目录没有可预览文件" : "请选择源目录并进入分类模式");
            imageView.setImageDrawable(null);
            imageView.setVisibility(View.VISIBLE);
            videoPlaceholder.setVisibility(View.GONE);
            return;
        }
        Models.FileItem item = files.get(currentIndex);
        previewTitle.setText(item.entry.name);
        previewInfo.setText(formatPreviewInfo(item));
        imageView.setImageDrawable(null);
        videoPlaceholder.setVisibility(View.GONE);
        if (delayedPreviewRender != null || suppressPreviewRender) {
            return;
        }
        if (Models.isImageFileName(item.entry.name)) {
            imageView.setVisibility(View.VISIBLE);
            imageView.setContentDescription("当前图片预览: " + item.entry.name + "。点按全屏查看");
            loadImage(item);
        } else if (Models.isVideoFileName(item.entry.name)) {
            imageView.setVisibility(View.GONE);
            videoPlaceholder.setVisibility(View.VISIBLE);
            videoPlaceholder.setContentDescription("视频: " + item.entry.name + "。点按打开");
            videoPlaceholder.setOnClickListener(view -> openPreview());
        } else {
            imageView.setVisibility(View.GONE);
        }
    }

    private void loadImage(Models.FileItem item) {
        if (status != null) status.setText("正在读取预览: " + item.entry.name);
        try {
            imageExecutor.execute(() -> {
                Bitmap cached = previewCache.loadImage(item.entry,
                        MAIN_PREVIEW_DECODE_LIMIT, MAIN_PREVIEW_DECODE_LIMIT, false);
                mainHandler.post(() -> {
                    if (!isCurrentItem(item)) return;
                    if (cached != null) {
                        previewCache.putImageInMemory(item.entry, cached);
                        showDecodedImage(cached);
                    } else {
                        fetchFileData(item, (ok, message, data) -> {
                            if (!isCurrentItem(item)) return;
                            if (!ok) {
                                previewInfo.setText(formatPreviewInfo(item) + "\n\n预览失败: " + message);
                                return;
                            }
                            decodeAndShowImage(item, data);
                        }, "正在读取 " + item.entry.name);
                    }
                });
            });
        } catch (RejectedExecutionException error) {
            previewInfo.setText(formatPreviewInfo(item) + "\n\n图片任务已停止。");
        }
    }

    private void decodeAndShowImage(Models.FileItem item, byte[] data) {
        decodeImageOnce(item, data, (image, cached) -> {
            if (!isCurrentItem(item)) {
                // A result that arrived after navigation/fullscreen was never assigned to the
                // current ImageView. Only non-cached results may be recycled here.
                if (!cached && image != null && !image.isRecycled()) image.recycle();
                return;
            }
            if (image == null) {
                previewInfo.setText(formatEntryInfo(item)
                        + "\n\n系统图片与媒体解码器均不支持此格式。");
            } else {
                showDecodedImage(image);
            }
        });
    }

    private void decodeImageOnce(Models.FileItem item, byte[] data, ImageDecodeCallback callback) {
        String key = downloadKey(item.entry);
        ArrayList<ImageDecodeCallback> waiting = inFlightImageDecodes.get(key);
        if (waiting != null) {
            waiting.add(callback);
            return;
        }
        waiting = new ArrayList<>();
        waiting.add(callback);
        inFlightImageDecodes.put(key, waiting);
        final int writeGeneration = cacheGeneration;
        try {
            imageExecutor.execute(() -> {
                // Large HEIF grid images can be tens of thousands of pixels wide. Keep the
                // interactive preview bounded; full-resolution decoding remains available to
                // the explicit conversion action.
                // The embedded preview is physically smaller than the full-screen viewer. Keep
                // it at 1024px so the returning Activity does not allocate a bitmap larger than
                // the one that the outgoing full-screen Activity is releasing.
                Bitmap image = ImageConverter.decodePreview(data,
                        MAIN_PREVIEW_DECODE_LIMIT, MAIN_PREVIEW_DECODE_LIMIT);
                boolean cached = false;
                if (image != null && writeGeneration == cacheGeneration && !previewActive) {
                    // The interactive bitmap is intentionally bounded. Do not write it to the
                    // full-quality conversion cache; conversion fetches/decodes the original.
                    cached = previewCache.putImageInMemory(item.entry, image);
                }
                Bitmap finalImage = image;
                boolean finalCached = cached;
                mainHandler.post(() -> finishImageDecode(key, finalImage, finalCached));
            });
        } catch (RejectedExecutionException error) {
            finishImageDecode(key, null, false);
        }
    }

    private void finishImageDecode(String key, Bitmap image, boolean cached) {
        ArrayList<ImageDecodeCallback> callbacks = inFlightImageDecodes.remove(key);
        if (callbacks == null) {
            // The result may still be referenced by an ImageView during the fullscreen
            // Activity transition. Let the bitmap become unreachable naturally instead of
            // recycling it while a render pass can still be using the native buffer.
            return;
        }
        for (ImageDecodeCallback callback : callbacks) callback.onResult(image, cached);
    }

    private boolean isCurrentItem(Models.FileItem item) {
        return !previewActive && !suppressPreviewRender && !isFinishing() && !isDestroyed()
                && currentIndex >= 0 && currentIndex < files.size()
                && files.get(currentIndex).entry.path.equals(item.entry.path);
    }

    private void showDecodedImage(Bitmap image) {
        imageView.setImageBitmap(image);
        imageView.setVisibility(View.VISIBLE);
        if (status != null) status.setText("预览已就绪，点按可全屏查看");
    }

    private void schedulePreviewRenderAfterFullscreen() {
        if (!mainScreenVisible || isFinishing() || isDestroyed()) return;
        if (delayedPreviewRender != null) mainHandler.removeCallbacks(delayedPreviewRender);
        delayedPreviewRender = () -> {
            delayedPreviewRender = null;
            if (mainScreenVisible && !isFinishing() && !isDestroyed()) renderCurrentFile();
        };
        // Give the outgoing Activity and its last native decoder a moment to release graphics
        // buffers before MainActivity allocates the next preview bitmap.
        mainHandler.postDelayed(delayedPreviewRender, FULLSCREEN_RETURN_RENDER_DELAY_MS);
    }

    private void openPreview() {
        if (currentIndex < 0 || currentIndex >= files.size()) return;
        ArrayList<Models.PreviewItem> viewItems = new ArrayList<>();
        int start = Math.max(0, currentIndex - MAX_PREVIEW_ITEMS / 2);
        int end = Math.min(files.size(), start + MAX_PREVIEW_ITEMS);
        start = Math.max(0, end - MAX_PREVIEW_ITEMS);
        for (int i = start; i < end; i++) {
            Models.FileItem item = files.get(i);
            Models.PreviewItem preview = new Models.PreviewItem(item.entry);
            preview.selectedTags.addAll(item.selectedTags);
            preview.tagsEditable = !item.submitted;
            viewItems.add(preview);
        }
        Intent intent = new Intent(this, PreviewActivity.class);
        activePreviewSessionId = PreviewSessionStore.create(
                viewItems, tagNames(), currentIndex - start);
        intent.putExtra(PreviewActivity.EXTRA_SESSION_ID, activePreviewSessionId);
        previewActive = true;
        ++cacheGeneration;
        imageView.setImageDrawable(null);
        try {
            startActivityForResult(intent, REQUEST_PREVIEW);
            // The drawable is detached before the transition starts. Recycle only after a short
            // renderer grace period and only while the full-screen Activity is still active.
            mainHandler.postDelayed(() -> {
                if (previewActive && imageView != null && imageView.getDrawable() == null) {
                    previewCache.clearMemoryAndRecycle();
                }
            }, DETACHED_BITMAP_RECYCLE_DELAY_MS);
        } catch (ActivityNotFoundException error) {
            previewActive = false;
            PreviewSessionStore.discard(activePreviewSessionId);
            activePreviewSessionId = null;
            renderCurrentFile();
            showError("无法打开全屏预览页面。");
        }
    }

    private ArrayList<String> tagNames() {
        ArrayList<String> names = new ArrayList<>();
        for (Models.TagMapping tag : tags) names.add(tag.name);
        return names;
    }

    private void fetchFileData(Models.FileItem item, OpenListClient.DownloadCallback callback,
                               String label) {
        fetchFileData(item, callback, label, false);
    }

    private void fetchFileData(Models.FileItem item, OpenListClient.DownloadCallback callback,
                               String label, boolean lowPriority) {
        String key = downloadKey(item.entry);
        ArrayList<OpenListClient.DownloadCallback> waiting = inFlightDownloads.get(key);
        if (waiting != null) {
            waiting.add(callback);
            return;
        }
        waiting = new ArrayList<>();
        waiting.add(callback);
        inFlightDownloads.put(key, waiting);
        try {
            cacheExecutor.execute(() -> {
                byte[] cached = previewCache.loadRawData(item.entry);
                mainHandler.post(() -> {
                    if (cached != null) {
                        completeDownload(key, true, "cached", cached);
                    } else {
                        beginRemoteDownload(item, key, label, lowPriority);
                    }
                });
            });
        } catch (RejectedExecutionException error) {
            completeDownload(key, false, "后台读取任务已停止", new byte[0]);
        }
    }

    private void beginRemoteDownload(Models.FileItem item, String key, String label,
                                     boolean lowPriority) {
        if (!item.entry.rawUrl.isEmpty()) {
            downloadEntry(item.entry, OpenListClient.resolveUrl(client.getBaseUrl(), item.entry.rawUrl),
                    key, label, lowPriority);
            return;
        }
        client.getFileInfo(item.entry.path, (ok, message, entry) -> {
            if (!ok || entry.rawUrl.isEmpty()) {
                completeDownload(key, false, ok ? "OpenList 没有返回下载地址。" : message, new byte[0]);
                return;
            }
            item.entry.rawUrl = entry.rawUrl;
            item.entry.thumb = entry.thumb;
            downloadEntry(item.entry, OpenListClient.resolveUrl(client.getBaseUrl(), entry.rawUrl),
                    key, label, lowPriority);
        });
    }

    private void downloadEntry(Models.RemoteEntry entry, java.net.URL url, String key, String label,
                               boolean lowPriority) {
        final int writeGeneration = cacheGeneration;
        if (status != null) status.setText(label);
        client.downloadUrl(url, (ok, message, data) -> {
            if (!ok) {
                completeDownload(key, false, message, data);
                return;
            }
            try {
                cacheExecutor.execute(() -> {
                    if (writeGeneration == cacheGeneration) previewCache.storeRawData(entry, data);
                    mainHandler.post(() -> completeDownload(key, true, message, data));
                });
            } catch (RejectedExecutionException error) {
                completeDownload(key, true, message, data);
            }
        }, (received, total) -> {
            long now = android.os.SystemClock.uptimeMillis();
            synchronized (this) {
                if (received != total && now - lastProgressUiUpdate < 120L) return;
                lastProgressUiUpdate = now;
            }
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (progress != null && preloadActiveCount == 0 && submitActiveCount == 0) {
                    progress.setVisibility(View.VISIBLE);
                    progress.setIndeterminate(total <= 0);
                    if (total > 0) {
                        progress.setMax(100);
                        progress.setProgress((int) Math.min(100, received * 100 / total));
                    }
                }
                if (status != null) status.setText(label + ": " + Models.formatBytes(received));
            });
        }, lowPriority);
    }

    private void completeDownload(String key, boolean ok, String message, byte[] data) {
        ArrayList<OpenListClient.DownloadCallback> callbacks = inFlightDownloads.remove(key);
        if (callbacks == null) return;
        if (!busy && preloadActiveCount == 0 && progress != null) progress.setVisibility(View.GONE);
        for (OpenListClient.DownloadCallback callback : callbacks) {
            callback.onResult(ok, message, data);
        }
    }

    private static String downloadKey(Models.RemoteEntry entry) {
        return entry.path + "\n" + entry.size + "\n" + entry.modified;
    }

    private void addTag() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), 0, dp(20), 0);
        EditText name = input("Tag 名称");
        EditText path = input("目标目录");
        path.setText("/");
        path.setFocusable(false);
        path.setClickable(true);
        path.setContentDescription("目标目录，点击选择目录");
        form.addView(labelled("Tag 名称", name));
        form.addView(labelled("目标目录", path));
        path.setOnClickListener(view -> pickDirectory(path.getText().toString(), "lastTagDirectory", path::setText));
        new MaterialAlertDialogBuilder(this).setTitle("新增 tag").setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String tagName = name.getText().toString().trim();
                    String target = Models.normalizeRemotePath(path.getText().toString());
                    if (tagName.isEmpty()) {
                        showError("tag 名称不能为空");
                        return;
                    }
                    for (Models.TagMapping tag : tags) {
                        if (tag.name.equals(tagName)) {
                            showError("tag 已存在");
                            return;
                        }
                    }
                    tags.add(new Models.TagMapping(tagName, target));
                    saveLocalSettings();
                    refreshTags();
                }).show();
    }

    private void manageTags() {
        if (tags.isEmpty()) {
            showError("当前没有可管理的 tag");
            return;
        }
        String[] labels = new String[tags.size()];
        for (int i = 0; i < tags.size(); i++) {
            labels[i] = tags.get(i).name + "  ->  " + tags.get(i).targetPath;
        }
        new MaterialAlertDialogBuilder(this).setTitle("管理 tag").setItems(labels, (dialog, which) ->
                new MaterialAlertDialogBuilder(this).setTitle(tags.get(which).name)
                        .setItems(new String[]{"编辑", "删除"}, (actionDialog, action) -> {
                            if (action == 0) editTag(which);
                            else removeTag(which);
                        }).show()).show();
    }

    private void editTag(int index) {
        if (index < 0 || index >= tags.size()) return;
        Models.TagMapping original = tags.get(index);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), 0, dp(20), 0);
        EditText name = input("Tag 名称");
        EditText path = input("目标目录");
        name.setText(original.name);
        path.setText(original.targetPath);
        path.setFocusable(false);
        path.setClickable(true);
        path.setContentDescription("目标目录，点击选择目录");
        form.addView(labelled("Tag 名称", name));
        form.addView(labelled("目标目录", path));
        path.setOnClickListener(view -> pickDirectory(path.getText().toString(), "lastTagDirectory", path::setText));
        new MaterialAlertDialogBuilder(this).setTitle("编辑 tag").setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String newName = name.getText().toString().trim();
                    String newPath = Models.normalizeRemotePath(path.getText().toString());
                    if (newName.isEmpty()) {
                        showError("tag 名称不能为空");
                        return;
                    }
                    for (int i = 0; i < tags.size(); i++) {
                        if (i != index && tags.get(i).name.equals(newName)) {
                            showError("tag 已存在");
                            return;
                        }
                    }
                    for (Models.FileItem item : files) {
                        for (int i = 0; i < item.selectedTags.size(); i++) {
                            if (item.selectedTags.get(i).equals(original.name)) {
                                item.selectedTags.set(i, newName);
                            }
                        }
                    }
                    tags.set(index, new Models.TagMapping(newName, newPath));
                    saveLocalSettings();
                    refreshTags();
                    renderFileList();
                }).show();
    }

    private void removeTag(int index) {
        if (index < 0 || index >= tags.size()) return;
        String name = tags.get(index).name;
        new MaterialAlertDialogBuilder(this).setTitle("删除 tag")
                .setMessage("删除后当前会话中已选择该 tag 的文件也会取消选择。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    tags.remove(index);
                    for (Models.FileItem item : files) item.selectedTags.remove(name);
                    saveLocalSettings();
                    refreshTags();
                    renderFileList();
                    updateStats();
                }).show();
    }

    private void refreshTags() {
        if (tagList == null) return;
        tagList.removeAllViews();
        boolean editing = tagEditMode;
        if (tags.isEmpty()) {
            TextView hint = text("请先新增 tag，并为 tag 填写目标目录。", 0xff505a59);
            hint.setPadding(dp(4), dp(10), dp(4), dp(10));
            tagList.addView(hint);
        }
        for (int start = 0; start < tags.size(); start += 2) {
            LinearLayout tagRow = new LinearLayout(this);
            tagRow.setOrientation(LinearLayout.HORIZONTAL);
            tagRow.setGravity(Gravity.CENTER_VERTICAL);
            for (int column = 0; column < 2; column++) {
                int tagIndex = start + column;
                if (tagIndex >= tags.size()) {
                    tagRow.addView(new View(this), new LinearLayout.LayoutParams(0, dp(48), 1));
                    continue;
                }
                Models.TagMapping tag = tags.get(tagIndex);
                FrameLayout tagCell = new FrameLayout(this);
                tagCell.setPadding(column > 0 ? dp(4) : 0, 0, 0, 0);
                MaterialCheckBox check = new MaterialCheckBox(this);
                check.setText(tag.name);
                check.setTextSize(13);
                check.setGravity(Gravity.CENTER_VERTICAL);
                check.setMinHeight(dp(48));
                check.setMinimumHeight(dp(48));
                check.setPadding(0, 0, 0, 0);
                check.setSingleLine(true);
                check.setEllipsize(android.text.TextUtils.TruncateAt.END);
                check.setContentDescription("tag " + tag.name + "，目标目录 " + tag.targetPath);
                check.setTag(tag.name);
                check.setVisibility(editing ? View.GONE : View.VISIBLE);
                Models.FileItem item = currentIndex >= 0 && currentIndex < files.size()
                        ? files.get(currentIndex) : null;
                check.setEnabled(classificationMode && item != null && !item.submitted && !busy);
                check.setChecked(item != null && item.selectedTags.contains(tag.name));
                check.setOnCheckedChangeListener((button, checked) -> {
                    if (currentIndex < 0 || currentIndex >= files.size()) return;
                    Models.FileItem selected = files.get(currentIndex);
                    if (selected.submitted) return;
                    if (checked && !selected.selectedTags.contains(tag.name)) selected.selectedTags.add(tag.name);
                    if (!checked) selected.selectedTags.remove(tag.name);
                    selected.failed = false;
                    selected.lastError = "";
                    renderFileList();
                    updateStats();
                });
                tagCell.addView(check, new FrameLayout.LayoutParams(-1, dp(48)));
                TextView editLabel = text(tag.name, getColorCompat(R.color.on_surface));
                editLabel.setTextSize(13);
                editLabel.setGravity(Gravity.CENTER_VERTICAL);
                editLabel.setSingleLine(true);
                editLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
                editLabel.setPadding(0, 0, dp(36), 0);
                editLabel.setContentDescription("编辑标签 " + tag.name + "，目标目录 " + tag.targetPath);
                editLabel.setClickable(true);
                editLabel.setFocusable(true);
                editLabel.setEnabled(!busy);
                editLabel.setVisibility(editing ? View.VISIBLE : View.GONE);
                editLabel.setOnClickListener(view -> {
                    int currentTagIndex = tags.indexOf(tag);
                    if (currentTagIndex >= 0) editTag(currentTagIndex);
                });
                tagCell.addView(editLabel, new FrameLayout.LayoutParams(-1, dp(48)));
                ImageButton remove = compactIconButton(android.R.drawable.ic_menu_delete,
                        "删除标签 " + tag.name);
                remove.setImageTintList(ColorStateList.valueOf(getColorCompat(R.color.error)));
                remove.setEnabled(!busy);
                remove.setVisibility(editing ? View.VISIBLE : View.GONE);
                remove.setOnClickListener(view -> {
                    int currentTagIndex = tags.indexOf(tag);
                    if (currentTagIndex >= 0) removeTag(currentTagIndex);
                });
                FrameLayout.LayoutParams removeParams = new FrameLayout.LayoutParams(
                        dp(40), dp(40), Gravity.END | Gravity.CENTER_VERTICAL);
                tagCell.addView(remove, removeParams);
                tagRow.addView(tagCell, new LinearLayout.LayoutParams(0, dp(48), 1));
            }
            tagList.addView(tagRow, new LinearLayout.LayoutParams(-1, dp(48)));
            if (start + 2 < tags.size()) {
                View divider = new View(this);
                divider.setBackgroundColor(getColorCompat(R.color.outline_variant));
                tagList.addView(divider, new LinearLayout.LayoutParams(-1, 1));
            }
        }
    }

    private void clearCurrentTags() {
        if (currentIndex >= 0 && currentIndex < files.size() && !files.get(currentIndex).submitted) {
            files.get(currentIndex).selectedTags.clear();
            files.get(currentIndex).failed = false;
            files.get(currentIndex).lastError = "";
            refreshTags();
            renderFileList();
            updateStats();
        }
    }

    private void setTagEditMode(boolean enabled) {
        if (busy) return;
        tagEditMode = enabled;
        if (tagEditButton != null) {
            tagEditButton.setContentDescription(tagEditMode ? "关闭标签编辑" : "编辑标签");
        }
        refreshTags();
    }

    private void showTagActions() {
        new MaterialAlertDialogBuilder(this).setTitle("标签操作")
                .setItems(new String[]{"管理标签", "清空当前文件标签"}, (dialog, which) -> {
                    if (which == 0) manageTags();
                    else clearCurrentTags();
                }).show();
    }

    private void updateClassificationButton() {
        if (enterButton == null) return;
        enterButton.setText(null);
        ((MaterialButton) enterButton).setIconResource(classificationMode
                ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        enterButton.setContentDescription(classificationMode ? "退出分类模式" : "进入分类模式");
    }

    private void savePending() {
        if (busy) return;
        ArrayList<Integer> pending = new ArrayList<>();
        boolean multi = false;
        int converted = 0;
        for (int i = 0; i < files.size(); i++) {
            Models.FileItem item = files.get(i);
            if (!item.submitted && !item.selectedTags.isEmpty()) {
                pending.add(i);
                multi |= item.selectedTags.size() > 1;
                if (convertOnSave && Models.isImageFileName(item.entry.name)) converted++;
            }
        }
        if (pending.isEmpty()) {
            showInfo("当前没有已选择但未提交的文件");
            return;
        }
        String message = "将并发处理 " + pending.size() + " 个文件（最多 " + submitConcurrency + " 个同时进行）。";
        if (multi) message += "多 tag 文件会复制到多个目标目录。";
        if (converted > 0) message += "图片保持原分辨率转 WebP，全部上传成功后删除源文件。";
        new MaterialAlertDialogBuilder(this).setTitle("确认保存").setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> startSubmitBatch(pending)).show();
    }

    private void startSubmitBatch(ArrayList<Integer> indexes) {
        ++preloadGeneration;
        submitQueue = new ArrayList<>(indexes);
        convertedUploadQueues.clear();
        submitQueuePosition = 0;
        submitActiveCount = 0;
        submitCompletedCount = 0;
        setBusy(true, "正在并发提交: 0 / " + submitQueue.size());
        if (progress != null) {
            progress.setIndeterminate(false);
            progress.setMax(Math.max(1, submitQueue.size()));
            progress.setProgress(0);
        }
        boolean convertsImages = false;
        if (convertOnSave) {
            for (int index : indexes) {
                if (index >= 0 && index < files.size()
                        && Models.isImageFileName(files.get(index).entry.name)) {
                    convertsImages = true;
                    break;
                }
            }
        }
        if (convertsImages) {
            suppressPreviewRender = true;
            releaseInteractivePreviewForLargeWork(this::startSubmitWorkers);
        } else {
            startSubmitWorkers();
        }
    }

    private void startSubmitWorkers() {
        while (submitActiveCount < submitConcurrency && submitQueuePosition < submitQueue.size()) {
            int index = submitQueue.get(submitQueuePosition++);
            if (index < 0 || index >= files.size()) {
                submitCompletedCount++;
                continue;
            }
            submitActiveCount++;
            if (status != null) status.setText("正在并发提交 " + submitCompletedCount + " / "
                    + submitQueue.size() + ": " + files.get(index).entry.name);
            submitFile(index, (ok, message) -> mainHandler.post(
                    () -> finishSubmittedFile(index, ok, message)));
        }
        if (submitActiveCount == 0 && submitCompletedCount >= submitQueue.size()) {
            int succeeded = 0;
            for (int index : submitQueue) {
                if (index >= 0 && index < files.size() && files.get(index).submitted) succeeded++;
            }
            int failed = submitQueue.size() - succeeded;
            setBusy(false, "保存完成");
            if (suppressPreviewRender) {
                suppressPreviewRender = false;
                renderCurrentFile();
            }
            showInfo("批量处理完成: 成功 " + succeeded + "，失败 " + failed
                    + (failed > 0 ? "。失败项已保留，可再次保存重试。" : "。"));
        }
    }

    private void finishSubmittedFile(int index, boolean ok, String message) {
        if (index >= 0 && index < files.size()) {
            Models.FileItem item = files.get(index);
            item.submitted = ok;
            item.failed = !ok;
            item.lastError = ok ? "" : message;
        }
        submitActiveCount = Math.max(0, submitActiveCount - 1);
        submitCompletedCount++;
        if (progress != null) {
            progress.setIndeterminate(false);
            progress.setMax(Math.max(1, submitQueue.size()));
            progress.setProgress(submitCompletedCount);
        }
        if (status != null) status.setText("并发提交: " + submitCompletedCount + " / "
                + submitQueue.size());
        renderFileList();
        updateStats();
        if (index == currentIndex) {
            refreshTags();
            if (!suppressPreviewRender) renderCurrentFile();
        }
        startSubmitWorkers();
    }

    private void submitFile(int index, OpenListClient.SimpleCallback callback) {
        if (index < 0 || index >= files.size()) {
            callback.onResult(false, "文件列表已经变化，请重新选择后提交");
            return;
        }
        Models.FileItem item = files.get(index);
        ArrayList<String> uniqueTargets = new ArrayList<>();
        ArrayList<String> selectedTargets = new ArrayList<>();
        for (String selected : item.selectedTags) {
            Models.TagMapping tag = findTag(selected);
            if (tag == null) {
                callback.onResult(false, "tag 没有绑定目标目录: " + selected);
                return;
            }
            selectedTargets.add(tag.targetPath);
            if (!uniqueTargets.contains(tag.targetPath)) uniqueTargets.add(tag.targetPath);
        }
        if (convertOnSave && Models.isImageFileName(item.entry.name)) {
            submitConverted(item, uniqueTargets, callback);
        } else if (item.selectedTags.size() == 1) {
            client.moveFile(sourcePath, item.entry.name, selectedTargets.get(0), overwrite, callback);
        } else {
            copyTargets(item, uniqueTargets, 0, callback);
        }
    }

    private void copyTargets(Models.FileItem item, ArrayList<String> targets, int position,
                             OpenListClient.SimpleCallback callback) {
        if (position >= targets.size()) {
            callback.onResult(true, "复制完成");
            return;
        }
        client.copyFile(Models.parentRemotePath(item.entry.path), item.entry.name,
                targets.get(position), overwrite, (ok, message) -> {
                    if (!ok) callback.onResult(false, message);
                    else copyTargets(item, targets, position + 1, callback);
                });
    }

    private void submitConverted(Models.FileItem item, ArrayList<String> targets,
                                 OpenListClient.SimpleCallback callback) {
        fetchFileData(item, (ok, message, data) -> {
            if (!ok) {
                callback.onResult(false, "获取待转换图片失败: " + message);
                return;
            }
            try {
                imageExecutor.execute(() -> {
                    final ImageConverter.Result converted;
                    // Network submission can remain concurrent, but full-resolution decode and
                    // encode must be serialized. Otherwise two 12 MP bitmaps can overlap while
                    // one worker compresses and another worker starts decoding.
                    synchronized (FULL_RESOLUTION_CONVERSION_LOCK) {
                        byte[] conversionData = data;
                        String hashSource = "原始文件";
                        if (ImageConverter.isHeifLike(data, item.entry.name)) {
                            conversionData = previewCache.loadDecodedJpeg(item.entry);
                            if (conversionData == null) {
                                conversionData = ImageConverter.decodeToFullQualityJpeg(data);
                                if (conversionData != null) {
                                    previewCache.storeDecodedJpeg(item.entry, conversionData);
                                }
                            }
                            hashSource = "HEIF 原分辨率解码 JPG 缓存";
                        }
                        converted = conversionData == null
                                ? conversionFailure(hashSource, "HEIF/AVIF 原分辨率解码缓存生成失败。")
                                : ImageConverter.convertToWebp(conversionData, hashSource);
                    }
                    mainHandler.post(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (!converted.ok) {
                            callback.onResult(false, converted.message);
                            return;
                        }
                        enqueueConvertedUpload(converted.outputName, () ->
                                uploadConverted(item, converted, targets, 0, (uploadOk, uploadMessage) -> {
                                    try {
                                        callback.onResult(uploadOk, uploadMessage);
                                    } finally {
                                        finishConvertedUpload(converted.outputName);
                                    }
                                }));
                    });
                });
            } catch (RejectedExecutionException error) {
                callback.onResult(false, "图片转换任务已停止");
            }
        }, "正在读取待转换图片");
    }

    private void enqueueConvertedUpload(String outputName, Runnable upload) {
        ArrayDeque<Runnable> queue = convertedUploadQueues.computeIfAbsent(
                outputName, ignored -> new ArrayDeque<>());
        queue.addLast(upload);
        if (queue.size() == 1) upload.run();
    }

    private void finishConvertedUpload(String outputName) {
        ArrayDeque<Runnable> queue = convertedUploadQueues.get(outputName);
        if (queue == null || queue.isEmpty()) return;
        queue.removeFirst();
        if (queue.isEmpty()) {
            convertedUploadQueues.remove(outputName);
        } else {
            queue.peekFirst().run();
        }
    }

    private void uploadConverted(Models.FileItem item, ImageConverter.Result converted,
                                 ArrayList<String> targets, int position,
                                 OpenListClient.SimpleCallback callback) {
        if (position >= targets.size()) {
            client.removeFile(Models.parentRemotePath(item.entry.path), item.entry.name,
                    (ok, message) -> callback.onResult(ok, ok
                            ? "已转 WebP 并处理 " + targets.size() + " 个目标目录: " + converted.outputName
                            : "转换上传已完成，但删除源文件失败: " + message));
            return;
        }
        String uploadPath = Models.joinRemotePath(targets.get(position), converted.outputName);
        client.listDirectory(targets.get(position), false, (ok, message, path, entries) -> {
            if (!ok) {
                callback.onResult(false, "检查目标目录失败: " + message);
                return;
            }
            boolean duplicate = false;
            for (Models.RemoteEntry entry : entries) {
                if (entry.isDir || !Models.lowerSuffix(entry.name).equals("webp")) {
                    continue;
                }
                if (entry.path.equals(item.entry.path)) {
                    continue;
                }
                String base = entry.name;
                int dot = base.lastIndexOf('.');
                if (dot > 0) base = base.substring(0, dot);
                if (base.equals(converted.sha256) || base.equals(converted.md5)) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) {
                uploadConverted(item, converted, targets, position + 1, callback);
                return;
            }
            if (Models.normalizeRemotePath(uploadPath)
                    .equals(Models.normalizeRemotePath(item.entry.path))) {
                callback.onResult(false, "当前文件已经是目标 WebP 文件，无需转换。");
                return;
            }
            client.uploadFile(uploadPath, converted.data, overwrite, (uploadOk, uploadMessage) -> {
                if (!uploadOk) callback.onResult(false, "上传 WebP 失败: " + uploadMessage);
                else uploadConverted(item, converted, targets, position + 1, callback);
            });
        });
    }

    private void downloadCurrent() {
        if (currentIndex < 0 || currentIndex >= files.size() || busy) return;
        Models.FileItem item = files.get(currentIndex);
        setBusy(true, "正在下载 " + item.entry.name);
        fetchFileData(item, (ok, message, data) -> {
            setBusy(false, "");
            if (!ok) {
                showError("下载失败: " + message);
                return;
            }
            pendingDownloadData = data;
            pendingDownloadName = item.entry.name;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE, item.entry.name);
            try {
                startActivityForResult(intent, REQUEST_CREATE_DOCUMENT);
            } catch (ActivityNotFoundException error) {
                pendingDownloadData = null;
                pendingDownloadName = null;
                showError("系统没有可用的文件保存界面。");
            }
        }, "正在下载 " + item.entry.name);
    }

    private void convertCurrent() {
        if (currentIndex < 0 || currentIndex >= files.size() || busy) return;
        Models.FileItem item = files.get(currentIndex);
        if (item.submitted) {
            showError("当前文件已经提交过，请刷新目录后再转换。");
            return;
        }
        if (!Models.isImageFileName(item.entry.name)) {
            showError("当前文件不是可转换的图片");
            return;
        }
        new MaterialAlertDialogBuilder(this).setTitle("仅转换当前图片")
                .setMessage("转 WebP 上传回原目录，成功后删除原文件。继续吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("转换", (dialog, which) -> {
                    final int conversionIndex = currentIndex;
                    ArrayList<String> targets = new ArrayList<>();
                    targets.add(Models.parentRemotePath(item.entry.path));
                    ++preloadGeneration;
                    setBusy(true, "正在转换当前图片: " + item.entry.name);
                    suppressPreviewRender = true;
                    releaseInteractivePreviewForLargeWork(() -> submitConverted(
                            item, targets, (ok, message) -> {
                        setBusy(false, "");
                        suppressPreviewRender = false;
                        if (conversionIndex >= 0 && conversionIndex < files.size()
                                && files.get(conversionIndex).entry.path.equals(item.entry.path)) {
                            item.submitted = ok;
                            item.failed = !ok;
                            item.lastError = ok ? "" : message;
                        }
                        renderFileList();
                        updateStats();
                        refreshTags();
                        renderCurrentFile();
                        if (ok) showInfo(message); else showError(message);
                    }));
                }).show();
    }

    private void releaseInteractivePreviewForLargeWork(Runnable continuation) {
        ++cacheGeneration;
        if (imageView == null) {
            previewCache.clearMemory();
            continuation.run();
            return;
        }
        imageView.setImageDrawable(null);
        mainHandler.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (imageView != null && imageView.getDrawable() == null) {
                previewCache.clearMemoryAndRecycle();
            }
            continuation.run();
        }, DETACHED_BITMAP_RECYCLE_DELAY_MS);
    }

    private void deleteCurrent() {
        if (currentIndex < 0 || currentIndex >= files.size() || busy) return;
        Models.FileItem item = files.get(currentIndex);
        if (item.submitted) {
            showError("当前文件已经提交过，源位置可能已变化，请刷新目录后再删除。");
            return;
        }
        final int deleteIndex = currentIndex;
        new MaterialAlertDialogBuilder(this).setTitle("确认删除远程文件")
                .setMessage("此操作会直接删除远程文件，无法撤销。\n\n文件: "
                        + item.entry.name + "\n路径: " + item.entry.path)
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) ->
                        new MaterialAlertDialogBuilder(this).setTitle("最终确认删除")
                                .setMessage("确认后将立即向 OpenList 提交删除请求。\n\n" + item.entry.path)
                                .setNegativeButton("取消", null)
                                .setPositiveButton("确认删除", (finalDialog, finalWhich) -> {
                                    ++preloadGeneration;
                                    setBusy(true, "正在删除远程文件: " + item.entry.name);
                                    client.removeFile(Models.parentRemotePath(item.entry.path), item.entry.name,
                                            (ok, message) -> {
                                                setBusy(false, "");
                                                if (!ok) {
                                                    if (deleteIndex >= 0 && deleteIndex < files.size()
                                                            && files.get(deleteIndex).entry.path.equals(item.entry.path)) {
                                                        item.failed = true;
                                                        item.lastError = message;
                                                    }
                                                    showError("删除失败: " + message);
                                                    renderFileList();
                                                    refreshTags();
                                                    updateStats();
                                                    renderCurrentFile();
                                                    return;
                                                }
                                                files.remove(item);
                                                currentIndex = Math.min(deleteIndex, files.size() - 1);
                                                currentPage = Math.max(0, currentIndex / PAGE_SIZE);
                                                renderFileList();
                                                updateStats();
                                                refreshTags();
                                                renderCurrentFile();
                                            });
                                }).show()).show();
    }

    private void showMoreActions() {
        if (busy) {
            showError("当前有任务正在进行，请等待完成");
            return;
        }
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, dp(4), 0, dp(4));
        TextView taskSection = text("工作区", getColorCompat(R.color.on_surface_variant));
        taskSection.setTextSize(12);
        taskSection.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        taskSection.setPadding(dp(16), dp(8), dp(16), dp(4));
        page.addView(taskSection);
        addSettingsAction(page, "分类任务", "源目录、分类状态与保存选项", this::showTaskSettings);
        addSettingsAction(page, "连接信息", client.getBaseUrl(), this::showConnectionDiagnostics);
        TextView localSection = text("本地数据", getColorCompat(R.color.on_surface_variant));
        localSection.setTextSize(12);
        localSection.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        localSection.setPadding(dp(16), dp(14), dp(16), dp(4));
        page.addView(localSection);
        addSettingsAction(page, "性能与缓存", "预热、提交并发和缓存状态", this::showPerformanceSettings);
        addSettingsAction(page, "清空未提交选择", "只清除本地 tag，不修改远程文件", this::clearAllPendingSelections);
        addSettingsAction(page, "清理预览缓存", "释放本地预览缓存空间", this::confirmClearPreviewCache);
        TextView aboutSection = text("应用", getColorCompat(R.color.on_surface_variant));
        aboutSection.setTextSize(12);
        aboutSection.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        aboutSection.setPadding(dp(16), dp(14), dp(16), dp(4));
        page.addView(aboutSection);
        addSettingsAction(page, "关于", "OpenList Sorter Android " + appVersionName(), () ->
                showInfo("关于", "OpenList Sorter Android " + appVersionName()
                        + "\n\n基于 Qt 版工作流实现的 OpenList 远程文件分类工具。"));
        addSettingsAction(page, "注销", "断开连接并关闭自动登录", this::logout, true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        AlertDialog settingsDialog = new MaterialAlertDialogBuilder(this).setTitle("设置")
                .setView(scroll).setNegativeButton("关闭", null).create();
        bindSettingsActions(page, settingsDialog);
        settingsDialog.show();
    }

    private void addSettingsAction(LinearLayout parent, String title, String summary, Runnable action) {
        addSettingsAction(parent, title, summary, action, false);
    }

    private void addSettingsAction(LinearLayout parent, String title, String summary,
                                   Runnable action, boolean danger) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(8), dp(12), dp(8));
        row.setMinimumHeight(dp(64));
        row.setClickable(true);
        row.setFocusable(true);
        row.setTag(action);
        TextView textBlock = new TextView(this);
        textBlock.setText(title + "\n" + summary);
        textBlock.setTextSize(14);
        textBlock.setTextColor(getColorCompat(danger ? R.color.error : R.color.on_surface));
        textBlock.setLineSpacing(0, 1.05f);
        textBlock.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textBlock.setMaxLines(2);
        row.addView(textBlock, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = text("›", getColorCompat(R.color.on_surface_variant));
        arrow.setTextSize(26);
        arrow.setGravity(Gravity.CENTER);
        arrow.setPadding(0, 0, 0, dp(2));
        row.addView(arrow, new LinearLayout.LayoutParams(dp(32), dp(48)));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        View divider = new View(this);
        divider.setBackgroundColor(getColorCompat(R.color.outline_variant));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, 1);
        dividerParams.setMargins(dp(16), 0, dp(16), 0);
        parent.addView(divider, dividerParams);
    }

    private void bindSettingsActions(View view, AlertDialog dialog) {
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof Runnable) {
                Runnable action = (Runnable) tag;
                child.setOnClickListener(ignored -> {
                    dialog.dismiss();
                    action.run();
                });
            }
            bindSettingsActions(child, dialog);
        }
    }

    private void showPerformanceSettings() {
        PreviewCache.Stats stats = previewCache.stats();
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(4), dp(24), 0);
        NumberPicker preloadPicker = numberPicker(1, 10, preloadConcurrency,
                "分页预热并发数");
        NumberPicker submitPicker = numberPicker(1, 4, submitConcurrency,
                "批量提交并发数");
        body.addView(settingRow("分页预热并发数", preloadPicker));
        body.addView(settingRow("批量提交并发数", submitPicker));
        TextView cacheInfo = text("缓存 " + stats.fileCount + " 项 · "
                + Models.formatBytes(stats.totalBytes) + " / "
                + Models.formatBytes(PreviewCache.DEFAULT_MAX_DISK_BYTES)
                + " · 内存 " + stats.memoryEntryCount + " 项"
                + "\n原分辨率解码并发 " + imageWorkerCount + " 路", 0xff505a59);
        cacheInfo.setPadding(0, dp(12), 0, dp(4));
        body.addView(cacheInfo);
        new MaterialAlertDialogBuilder(this).setTitle("性能与缓存")
                .setView(body)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    int previousPreload = preloadConcurrency;
                    preloadConcurrency = preloadPicker.getValue();
                    submitConcurrency = submitPicker.getValue();
                    saveLocalSettings();
                    if (classificationMode && !busy && previousPreload != preloadConcurrency) {
                        preloadCurrentPage();
                    }
                }).show();
    }

    private NumberPicker numberPicker(int min, int max, int value, String description) {
        NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(min);
        picker.setMaxValue(max);
        picker.setValue(value);
        picker.setWrapSelectorWheel(false);
        picker.setContentDescription(description);
        return picker;
    }

    private LinearLayout settingRow(String label, View control) {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(label, 0xff171c1b);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(control, new LinearLayout.LayoutParams(dp(104), dp(96)));
        return row;
    }

    private void confirmClearPreviewCache() {
        if (busy) {
            showError("当前有任务正在进行，请稍后清理缓存");
            return;
        }
        PreviewCache.Stats stats = previewCache.stats();
        new MaterialAlertDialogBuilder(this).setTitle("清理预览缓存")
                .setMessage("将删除 " + stats.fileCount + " 项缓存（"
                        + Models.formatBytes(stats.totalBytes) + "）。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清理", (dialog, which) -> clearPreviewCache()).show();
    }

    private void clearPreviewCache() {
        ++preloadGeneration;
        ++cacheGeneration;
        preloadQueue.clear();
        if (status != null) status.setText("正在清理预览缓存");
        try {
            cacheExecutor.execute(() -> {
                previewCache.clear();
                mainHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        if (status != null) status.setText("预览缓存已清理");
                        showInfo("预览缓存已清理");
                    }
                });
            });
        } catch (RejectedExecutionException error) {
            showError("缓存任务已停止");
        }
    }

    private void showConnectionDiagnostics() {
        setBusy(true, "正在检测连接...");
        client.fetchConnectionDiagnostics(diagnostics -> {
            setBusy(false, "");
            String detail = "Endpoint: " + diagnostics.endpoint
                    + "\n状态: " + (diagnostics.connected ? "已连接" : "未连接")
                    + "\n延迟: " + (diagnostics.latencyMs >= 0 ? diagnostics.latencyMs + " ms" : "未知")
                    + "\nIP: " + (diagnostics.ipAddress.isEmpty() ? "未知" : diagnostics.ipAddress)
                    + "\n远端版本: " + (diagnostics.version.isEmpty() ? "未知" : diagnostics.version)
                    + "\n站点标题: " + (diagnostics.siteTitle.isEmpty() ? "未知" : diagnostics.siteTitle)
                    + (diagnostics.message.isEmpty() ? "" : "\n信息: " + diagnostics.message);
            showInfo("连接信息", detail);
        });
    }

    private void clearAllPendingSelections() {
        int count = 0;
        for (Models.FileItem item : files) {
            if (!item.submitted && !item.selectedTags.isEmpty()) count++;
        }
        if (count == 0) {
            showInfo("当前没有待清空的选择");
            return;
        }
        final int pending = count;
        new MaterialAlertDialogBuilder(this).setTitle("清空全部未提交选择")
                .setMessage("将清空 " + pending + " 个文件的本地 tag 选择，不会修改远程文件。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> {
                    for (Models.FileItem item : files) {
                        if (!item.submitted) {
                            item.selectedTags.clear();
                            item.failed = false;
                            item.lastError = "";
                        }
                    }
                    refreshTags();
                    renderFileList();
                    updateStats();
                }).show();
    }

    private void logout() {
        if (busy) {
            showError("当前有任务正在进行，请等待完成");
            return;
        }
        new MaterialAlertDialogBuilder(this).setTitle("确认注销")
                .setMessage("将断开连接并关闭自动登录，已保存的连接信息会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("注销", (dialog, which) -> {
                    ConnectionStore.disableAutoLogin(this);
                    profile.autoLogin = false;
                    ++preloadGeneration;
                    client.shutdown();
                    client = new OpenListClient();
                    showConnectionScreen();
                }).show();
    }

    private String appVersionName() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            return "";
        }
    }

    private static ImageConverter.Result conversionFailure(String hashSource, String message) {
        ImageConverter.Result result = new ImageConverter.Result();
        result.hashSource = hashSource;
        result.message = message;
        return result;
    }

    private Models.TagMapping findTag(String name) {
        for (Models.TagMapping tag : tags) if (tag.name.equals(name)) return tag;
        return null;
    }

    private int pendingCount() {
        int count = 0;
        for (Models.FileItem item : files) if (!item.submitted && !item.selectedTags.isEmpty()) count++;
        return count;
    }

    private void updateStats() {
        if (statsView == null) return;
        int submitted = 0;
        int pending = 0;
        for (Models.FileItem item : files) {
            if (item.submitted) submitted++;
            else if (!item.selectedTags.isEmpty()) pending++;
        }
        statsView.setText("总文件 " + files.size() + " | 已提交 " + submitted + " | 待保存 "
                + pending + " | 未处理 " + (files.size() - submitted - pending)
                + " | 当前 " + (currentIndex < 0 ? 0 : currentIndex + 1));
        Models.FileItem current = currentIndex >= 0 && currentIndex < files.size()
                ? files.get(currentIndex) : null;
        if (saveButton != null) saveButton.setEnabled(classificationMode && pending > 0 && !busy);
        if (tagEditButton != null) tagEditButton.setEnabled(!busy);
        if (sourcePathView != null) sourcePathView.setEnabled(!classificationMode && !busy);
        if (visiblePathView != null) visiblePathView.setEnabled(!classificationMode && !busy);
        if (downloadCurrentButton != null) downloadCurrentButton.setEnabled(current != null && !busy);
        if (convertCurrentButton != null) convertCurrentButton.setEnabled(current != null && !current.submitted
                && Models.isImageFileName(current.entry.name) && !busy);
        if (deleteCurrentButton != null) deleteCurrentButton.setEnabled(current != null && !current.submitted && !busy);
        if (previousFileButton != null) previousFileButton.setEnabled(currentIndex > 0 && !busy);
        if (nextFileButton != null) nextFileButton.setEnabled(currentIndex >= 0
                && currentIndex < files.size() - 1 && !busy);
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        if (progress != null) {
            progress.setVisibility(value ? View.VISIBLE : View.GONE);
            progress.setIndeterminate(true);
        }
        if (status != null && message != null && !message.isEmpty()) status.setText(message);
        if (enterButton != null) enterButton.setEnabled(!value);
        updateStats();
        if (fileList != null) renderFileList();
        if (tagList != null) refreshTags();
    }

    private void showInfo(String message) {
        if (status != null) status.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void showError(String message) {
        if (status != null) status.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private String formatEntryInfo(Models.FileItem item) {
        return "文件名: " + item.entry.name + "\n路径: " + item.entry.path + "\n类型: "
                + Models.fileType(item.entry) + "\n大小: " + Models.formatBytes(item.entry.size)
                + "\n修改时间: " + (item.entry.modified.isEmpty() ? "未知" : item.entry.modified)
                + "\n状态: " + Models.statusText(item)
                + (item.lastError.isEmpty() ? "" : "\n错误: " + item.lastError);
    }

    private String formatPreviewInfo(Models.FileItem item) {
        return "路径: " + item.entry.path + "\n类型: " + Models.fileType(item.entry)
                + "    大小: " + Models.formatBytes(item.entry.size) + "\n状态: "
                + Models.statusText(item)
                + (item.lastError.isEmpty() ? "" : "\n错误: " + item.lastError);
    }

    private void showInfo(String title, String message) {
        new MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message).setPositiveButton("确定", null).show();
    }

    private LinearLayout baseRoot() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        value.setBackgroundResource(R.color.page_background);
        return value;
    }

    private void applySystemBarInsets(View view) {
        final int left = view.getPaddingLeft();
        final int top = view.getPaddingTop();
        final int right = view.getPaddingRight();
        final int bottom = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (target, windowInsets) -> {
            Insets navigation = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars());
            Insets cutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout());
            // Keep the left/right cutout safe area while the page background still reaches the
            // hidden status-bar edge. This matters on landscape devices with a camera notch.
            int safeLeft = Math.max(cutout.left, navigation.left);
            int safeRight = Math.max(cutout.right, navigation.right);
            target.setPadding(left + safeLeft, top, right + safeRight,
                    bottom + navigation.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    private void applyCutoutInsets(View view) {
        final int left = view.getPaddingLeft();
        final int top = view.getPaddingTop();
        final int right = view.getPaddingRight();
        ViewCompat.setOnApplyWindowInsetsListener(view, (target, insets) -> {
            Insets cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            target.setPadding(left + Math.max(cutout.left, bars.left),
                    top + cutout.top,
                    right + Math.max(cutout.right, bars.right), target.getPaddingBottom());
            return insets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    private MaterialPanel panel() {
        MaterialPanel value = new MaterialPanel(this);
        value.setCardBackgroundColor(getColorCompat(R.color.surface));
        value.setCardElevation(0);
        value.setRadius(dp(6));
        value.setStrokeWidth(dp(1));
        value.setStrokeColor(getColorCompat(R.color.outline_variant));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(8), 0, dp(8));
        value.setLayoutParams(params);
        return value;
    }

    private boolean isCompactLandscape() {
        return !isWideWorkspace();
    }

    private LinearLayout topBand() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        value.setBackgroundColor(getColorCompat(R.color.surface));
        value.setPadding(dp(16), dp(4), dp(16), 0);
        return value;
    }

    private LinearLayout row() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.HORIZONTAL);
        value.setGravity(Gravity.CENTER_VERTICAL);
        return value;
    }

    private TextView heading(String value, int size) {
        TextView result = text(value, getColorCompat(R.color.on_surface));
        result.setTextSize(size);
        result.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        result.setLetterSpacing(0f);
        result.setPadding(0, dp(4), 0, dp(8));
        return result;
    }

    private TextView text(String value, int color) {
        TextView result = new TextView(this);
        result.setText(value);
        result.setTextColor(color);
        result.setTextSize(14);
        result.setLineSpacing(0, 1.12f);
        result.setPadding(dp(4), dp(5), dp(4), dp(5));
        return result;
    }

    private EditText input(String hint) {
        TextInputEditText result = new TextInputEditText(this);
        result.setHint(hint);
        result.setSingleLine(true);
        result.setTextSize(15);
        return result;
    }

    private TextInputLayout labelled(String label, View child) {
        TextInputLayout value = new TextInputLayout(this);
        value.setHint(label);
        value.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        value.setBoxCornerRadii(dp(5), dp(5), dp(5), dp(5));
        if (child instanceof TextView) {
            TextView field = (TextView) child;
            CharSequence placeholder = field.getHint();
            if (placeholder != null && !label.contentEquals(placeholder)) {
                value.setPlaceholderText(placeholder);
            }
            field.setHint(null);
        }
        if (child.getId() == View.NO_ID) child.setId(View.generateViewId());
        value.addView(child, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(4), 0, dp(4));
        value.setLayoutParams(params);
        return value;
    }

    private Button button(String label) {
        MaterialButton result = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        result.setText(label);
        result.setTextColor(getColorCompat(R.color.primary));
        result.setStrokeColor(ColorStateList.valueOf(getColorCompat(R.color.outline_variant)));
        result.setCornerRadius(dp(5));
        result.setAllCaps(false);
        result.setTextSize(14);
        result.setMinHeight(dp(48));
        result.setMinimumHeight(dp(48));
        result.setInsetTop(0);
        result.setInsetBottom(0);
        return result;
    }

    private Button iconButton(int drawableRes, String description) {
        MaterialButton result = new MaterialButton(this);
        result.setIconResource(drawableRes);
        result.setIconTint(ColorStateList.valueOf(getColorCompat(R.color.primary)));
        result.setIconSize(dp(18));
        result.setIconPadding(0);
        result.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_TOP);
        result.setGravity(Gravity.CENTER);
        result.setPadding(0, 0, 0, 0);
        // Keep a stable 48dp touch target while leaving the visual bounds frameless.
        result.setMinWidth(dp(48));
        result.setMinimumWidth(dp(48));
        result.setMinHeight(dp(48));
        result.setMinimumHeight(dp(48));
        result.setInsetTop(0);
        result.setInsetBottom(0);
        result.setCornerRadius(0);
        result.setStrokeWidth(0);
        result.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        result.setStrokeColor(ColorStateList.valueOf(Color.TRANSPARENT));
        result.setElevation(0f);
        result.setContentDescription(description);
        return result;
    }

    private ImageButton compactIconButton(int drawableRes, String description) {
        ImageButton result = new ImageButton(this);
        result.setImageResource(drawableRes);
        result.setImageTintList(ColorStateList.valueOf(getColorCompat(R.color.primary)));
        result.setScaleType(ImageView.ScaleType.CENTER);
        result.setPadding(0, 0, 0, 0);
        result.setMinimumWidth(dp(40));
        result.setMinimumHeight(dp(40));
        result.setBackgroundColor(Color.TRANSPARENT);
        result.setContentDescription(description);
        return result;
    }

    private Button primaryButton(String label) {
        MaterialButton result = new MaterialButton(this);
        result.setText(label);
        result.setTextColor(getColorCompat(R.color.on_primary));
        result.setBackgroundTintList(ColorStateList.valueOf(getColorCompat(R.color.primary)));
        result.setCornerRadius(dp(5));
        result.setAllCaps(false);
        result.setTextSize(14);
        result.setMinHeight(dp(48));
        result.setMinimumHeight(dp(48));
        result.setInsetTop(0);
        result.setInsetBottom(0);
        result.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return result;
    }

    private RadioButton radio(String label) {
        MaterialRadioButton result = new MaterialRadioButton(this);
        result.setText(label);
        result.setAllCaps(false);
        return result;
    }

    private final class FileListAdapter extends RecyclerView.Adapter<FileListAdapter.Holder> {
        final class Holder extends RecyclerView.ViewHolder {
            final MaterialCardView card;
            final TextView nameLine;
            final TextView tagLine;

            Holder(MaterialCardView card, TextView nameLine, TextView tagLine) {
                super(card);
                this.card = card;
                this.nameLine = nameLine;
                this.tagLine = tagLine;
            }
        }

        @Override
        public int getItemCount() {
            if (!classificationMode || files.isEmpty()) return 0;
            int start = currentPage * PAGE_SIZE;
            return Math.min(files.size(), start + PAGE_SIZE) - start;
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            MaterialCardView card = new MaterialCardView(MainActivity.this);
            card.setRadius(dp(4));
            card.setCardElevation(0);
            card.setStrokeWidth(dp(1));
            card.setStrokeColor(getColorCompat(R.color.outline_variant));
            card.setCardBackgroundColor(getColorCompat(R.color.surface));
            LinearLayout inner = new LinearLayout(MainActivity.this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setPadding(dp(10), dp(8), dp(10), dp(8));
            TextView nameLine = new TextView(MainActivity.this);
            nameLine.setTextSize(14);
            nameLine.setTextColor(getColorCompat(R.color.on_surface));
            nameLine.setSingleLine(true);
            nameLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView tagLine = new TextView(MainActivity.this);
            tagLine.setTextSize(12);
            tagLine.setTextColor(getColorCompat(R.color.on_surface_variant));
            tagLine.setSingleLine(true);
            tagLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
            inner.addView(nameLine);
            inner.addView(tagLine);
            card.addView(inner);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(-1, -2);
            params.setMargins(0, 0, 0, dp(6));
            card.setLayoutParams(params);
            card.setClickable(true);
            card.setFocusable(true);
            return new Holder(card, nameLine, tagLine);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            int fileIndex = currentPage * PAGE_SIZE + position;
            if (fileIndex >= files.size()) {
                holder.card.setVisibility(View.INVISIBLE);
                return;
            }
            holder.card.setVisibility(View.VISIBLE);
            Models.FileItem item = files.get(fileIndex);
            holder.nameLine.setText((fileIndex + 1) + ". " + Models.statusText(item)
                    + "  " + item.entry.name);
            holder.tagLine.setText(item.selectedTags.isEmpty()
                    ? "" : "标签：" + String.join("、", item.selectedTags));
            holder.tagLine.setVisibility(item.selectedTags.isEmpty() ? View.GONE : View.VISIBLE);
            boolean current = fileIndex == currentIndex;
            holder.card.setStrokeColor(getColorCompat(
                    current ? R.color.primary : R.color.outline_variant));
            holder.card.setStrokeWidth(dp(current ? 2 : 1));
            final int target = fileIndex;
            holder.card.setOnClickListener(view -> setCurrentIndex(target));
        }
    }

    private static final class MaterialPanel extends MaterialCardView {
        private final LinearLayout content;

        MaterialPanel(Context context) {
            super(context);
            content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
            super.addView(content, new ViewGroup.LayoutParams(-1, -2));
        }

        void fillHeight() {
            ViewGroup.LayoutParams params = content.getLayoutParams();
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            content.setLayoutParams(params);
        }

        void setInnerPadding(int left, int top, int right, int bottom) {
            content.setPadding(left, top, right, bottom);
        }

        @Override
        public void addView(View child) {
            content.addView(child);
        }

        @Override
        public void addView(View child, ViewGroup.LayoutParams params) {
            content.addView(child, params);
        }

        private static int dp(Context context, int value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int getColorCompat(int resId) {
        return getColor(resId);
    }
}
