package com.openlistsorter.app.ui;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.openlistsorter.app.R;
import com.openlistsorter.app.data.ConnectionProfile;
import com.openlistsorter.app.data.ConnectionStore;
import com.openlistsorter.app.data.ImageConverter;
import com.openlistsorter.app.data.Models;
import com.openlistsorter.app.data.MediaTypeStore;
import com.openlistsorter.app.data.PreviewCache;
import com.openlistsorter.app.network.OpenListClient;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 全屏点开查看页面：横屏，可缩放图片，上一张/下一张，顶部安全区预留。 */
public final class PreviewActivity extends Activity {
    public static final String EXTRA_ITEMS = "items";
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_TAGS = "tags";
    public static final String EXTRA_SESSION_ID = "preview_session_id";
    public static final String EXTRA_TOKEN = "preview_token";
    public static final String EXTRA_ENDPOINT = "preview_endpoint";
    public static final String EXTRA_RESULT_ITEMS = "result_items";
    public static final String EXTRA_RESULT_PATH = "result_path";
    private static final String STATE_TAG_PANEL_POSITIONED = "tag_panel_positioned";
    private static final String STATE_TAG_PANEL_RIGHT = "tag_panel_right";
    private static final String STATE_TAG_PANEL_LEFT = "tag_panel_left";
    private static final String STATE_TAG_PANEL_TOP = "tag_panel_top";
    private static final long DETACHED_BITMAP_RECYCLE_DELAY_MS = 500L;
    private static final long TOP_BAR_HIDE_DELAY_MS = 2600L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private OpenListClient client;
    private PreviewCache previewCache;
    private ThreadPoolExecutor imageExecutor;
    private ExecutorService prefetchWriteExecutor;

    private ArrayList<Models.PreviewItem> items = new ArrayList<>();
    private final ArrayList<Models.RemoteEntry> entries = new ArrayList<>();
    private volatile int index;

    private TextView titleView;
    private TextView counterView;
    private LinearLayout topBar;
    private MaterialButton prevButton;
    private MaterialButton nextButton;
    private ZoomImageView imageView;
    private PlayerView videoView;
    private ExoPlayer videoPlayer;
    private LinearLayout progressHost;
    private TextView errorView;
    private MaterialButton tagToggle;
    private MaterialCardView tagPanel;
    private GridLayout tagGroup;
    private MaterialButton downloadAction;
    private MaterialButton convertAction;
    private MaterialButton deleteAction;
    private MaterialButton actionPreviousButton;
    private MaterialButton actionNextButton;
    private FrameLayout overlayHost;
    private FrameLayout imageHost;
    private View actionRow;
    private ArrayList<String> tagNames = new ArrayList<>();
    private volatile int loadingGeneration;
    private String sessionId;
    private String sessionToken = "";
    private String sessionEndpoint = "";
    private int previewDecodeLimit = 1536;
    private int preloadConcurrency = 3;
    private volatile boolean resourcesReleased;
    private volatile boolean exiting;
    private boolean updatingTagChecks;
    private boolean resultSent;
    private boolean actionBusy;
    private int videoRetryTarget = -1;
    private boolean videoRetrying;
    private final Set<String> prefetchInFlight = new HashSet<>();
    private Insets overlaySafeInsets = Insets.NONE;
    private boolean tagPanelPositioned;
    private boolean tagPanelDockedRight = true;
    private int tagPanelLeft;
    private int tagPanelTop;
    private boolean draggingTagPanel;
    private float tagDragDownX;
    private float tagDragDownY;
    private int tagDragStartLeft;
    private int tagDragStartTop;
    private final Runnable persistRunnable = this::persistSessionNow;
    private final Runnable hideTopBarRunnable = this::hideTopBar;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            tagPanelPositioned = state.getBoolean(STATE_TAG_PANEL_POSITIONED, false);
            tagPanelDockedRight = state.getBoolean(STATE_TAG_PANEL_RIGHT, true);
            tagPanelLeft = state.getInt(STATE_TAG_PANEL_LEFT, 0);
            tagPanelTop = state.getInt(STATE_TAG_PANEL_TOP, 0);
        }
        PreviewSessionStore.initialize(this);
        configureFullscreenWindow();
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        int memoryClassMb = activityManager == null ? 256 : activityManager.getMemoryClass();
        previewDecodeLimit = memoryClassMb < 384 ? 1024 : 1280;
        // Keep one predictable bound across devices. 3072px produces a 36 MiB ARGB buffer
        // before cache/window overhead and is unsafe when the user browses several images.
        // ImageDecoder/libheif work can retain large native buffers until a decode returns.
        // Keep this latest-image pipeline single threaded so quickly browsing many files never
        // runs several high-resolution decoders at the same time.
        imageExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable, "preview-image");
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                });
        prefetchWriteExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "preview-prefetch-cache");
            thread.setPriority(Thread.NORM_PRIORITY - 2);
            return thread;
        });
        client = new OpenListClient();
        previewCache = new PreviewCache(this);
        ConnectionProfile profile = ConnectionStore.load(this);
        MediaTypeStore.load(this);

        sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
        sessionToken = getIntent().getStringExtra(EXTRA_TOKEN);
        if (sessionToken == null) sessionToken = "";
        sessionEndpoint = getIntent().getStringExtra(EXTRA_ENDPOINT);
        if (sessionEndpoint == null || sessionEndpoint.isEmpty()) sessionEndpoint = profile.endpoint;
        if (sessionToken.isEmpty()) sessionToken = profile.token;
        client.setBaseUrl(sessionEndpoint);
        client.setToken(sessionToken);
        preloadConcurrency = Math.max(1, Math.min(10,
                getSharedPreferences("task", MODE_PRIVATE)
                        .getInt("preloadConcurrency", 3)));
        PreviewSessionStore.Session session = PreviewSessionStore.peek(sessionId);
        if (session != null) {
            items = session.items;
            tagNames = session.tagNames;
            index = session.index;
        } else {
            ArrayList<Models.PreviewItem> passed = getIntent().getParcelableArrayListExtra(EXTRA_ITEMS);
            if (passed != null) items = passed;
            ArrayList<String> passedTags = getIntent().getStringArrayListExtra(EXTRA_TAGS);
            if (passedTags != null) tagNames = passedTags;
            index = Math.max(0, getIntent().getIntExtra(EXTRA_INDEX, 0));
        }
        entries.clear();
        for (Models.PreviewItem item : items) entries.add(item.toEntry());
        if (index >= items.size()) index = Math.max(0, items.size() - 1);

        buildUi();
        render();
    }

    private void buildUi() {
        overlayHost = new FrameLayout(this);
        overlayHost.setBackgroundColor(0xff101413);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff101413);
        overlayHost.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0xff1b211f);

        MaterialButton back = (MaterialButton) iconButton(
                androidx.appcompat.R.drawable.abc_ic_ab_back_material, "返回");
        back.setOnClickListener(v -> finish());
        topBar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(16);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(dp(8), 0, dp(8), 0);
        topBar.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));

        prevButton = (MaterialButton) iconButton(android.R.drawable.ic_media_previous, "上一张");
        nextButton = (MaterialButton) iconButton(android.R.drawable.ic_media_next, "下一张");
        counterView = new TextView(this);
        counterView.setTextColor(Color.WHITE);
        counterView.setTextSize(14);
        counterView.setGravity(Gravity.CENTER);
        counterView.setMinWidth(dp(64));
        topBar.addView(prevButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        topBar.addView(counterView);
        topBar.addView(nextButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        prevButton.setOnClickListener(v -> move(-1));
        nextButton.setOnClickListener(v -> move(1));
        topBar.setOnClickListener(v -> showTopBarTemporarily());

        imageHost = new FrameLayout(this);
        imageHost.setBackgroundColor(0xff101413);

        imageView = new ZoomImageView(this);
        imageView.setBackgroundColor(0xff101413);
        imageView.setContentDescription("全屏图片预览，双指缩放，拖动查看，双击复位，单击显示或隐藏顶栏");
        imageView.setTapListener(this::toggleTopBar);
        imageHost.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        videoView = new PlayerView(this);
        videoView.setUseController(true);
        videoView.setControllerAutoShow(true);
        videoView.setBackgroundColor(0x1a000000);
        videoView.setContentDescription("视频播放器，支持拖动进度");
        videoView.setVisibility(View.GONE);
        imageHost.addView(videoView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        progressHost = new LinearLayout(this);
        progressHost.setOrientation(LinearLayout.VERTICAL);
        progressHost.setGravity(Gravity.CENTER);
        progressHost.setPadding(dp(12), dp(5), dp(12), dp(5));
        progressHost.setBackgroundColor(0xcc1b211f);
        progressHost.setVisibility(View.GONE);
        TextView loading = new TextView(this);
        loading.setText("正在读取...");
        loading.setTextColor(Color.WHITE);
        loading.setTextSize(15);
        progressHost.addView(loading);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        progressParams.setMargins(dp(8), dp(8), dp(8), 0);
        imageHost.addView(progressHost, progressParams);

        tagToggle = compactIconButton(android.R.drawable.ic_menu_edit, "展开标签选择");
        tagToggle.setOnClickListener(v -> {
            boolean expanded = tagPanel.getVisibility() == View.VISIBLE;
            tagPanel.setVisibility(expanded ? View.GONE : View.VISIBLE);
            tagToggle.setContentDescription(expanded ? "展开标签选择" : "收起标签选择");
            if (!expanded) {
                tagPanel.post(() -> {
                    if (!tagPanelPositioned) dockTagPanel(true);
                    else clampTagPanelPosition();
                });
            }
        });
        LinearLayout actionRow = new LinearLayout(this);
        this.actionRow = actionRow;
        actionRow.setOrientation(LinearLayout.VERTICAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setPadding(0, dp(2), 0, dp(2));
        actionPreviousButton = compactIconButton(android.R.drawable.ic_media_previous, "上一张");
        actionNextButton = compactIconButton(android.R.drawable.ic_media_next, "下一张");
        downloadAction = compactIconButton(android.R.drawable.ic_menu_save, "下载当前文件");
        convertAction = compactIconButton(android.R.drawable.ic_menu_manage, "转换当前图片为 WebP");
        deleteAction = compactIconButton(android.R.drawable.ic_menu_delete, "删除当前文件");
        styleFullscreenActionButton(downloadAction, Color.WHITE);
        styleFullscreenActionButton(convertAction, Color.WHITE);
        styleFullscreenActionButton(deleteAction, 0xffff8a80);
        styleFullscreenActionButton(tagToggle, Color.WHITE);
        styleFullscreenActionButton(actionPreviousButton, Color.WHITE);
        styleFullscreenActionButton(actionNextButton, Color.WHITE);
        actionPreviousButton.setOnClickListener(v -> move(-1));
        actionNextButton.setOnClickListener(v -> move(1));
        downloadAction.setOnClickListener(v -> downloadCurrent());
        convertAction.setOnClickListener(v -> convertCurrent());
        deleteAction.setOnClickListener(v -> deleteCurrent());
        actionRow.addView(actionPreviousButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        actionRow.addView(downloadAction, new LinearLayout.LayoutParams(dp(44), dp(44)));
        actionRow.addView(convertAction, new LinearLayout.LayoutParams(dp(44), dp(44)));
        actionRow.addView(deleteAction, new LinearLayout.LayoutParams(dp(44), dp(44)));
        actionRow.addView(tagToggle, new LinearLayout.LayoutParams(dp(44), dp(44)));
        actionRow.addView(actionNextButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        FrameLayout.LayoutParams actionParams = new FrameLayout.LayoutParams(
                dp(44), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.END);
        actionParams.setMargins(0, 0, dp(16), 0);
        imageHost.addView(actionRow, actionParams);

        tagPanel = new MaterialCardView(this);
        tagPanel.setCardBackgroundColor(0xf21b211f);
        tagPanel.setRadius(dp(8));
        tagPanel.setVisibility(View.GONE);
        LinearLayout tagContent = new LinearLayout(this);
        tagContent.setOrientation(LinearLayout.VERTICAL);
        tagContent.setPadding(dp(8), dp(2), dp(8), dp(4));
        View tagDragHandle = new View(this);
        tagDragHandle.setContentDescription("拖动标签面板");
        tagDragHandle.setBackgroundColor(0x33111111);
        tagDragHandle.setOnTouchListener((view, event) -> handleTagPanelTouch(event));
        tagDragHandle.setOnClickListener(v -> showTopBarTemporarily());
        tagContent.addView(tagDragHandle, new LinearLayout.LayoutParams(-1, dp(24)));
        ScrollView tagScroll = new ScrollView(this);
        tagScroll.setClipToPadding(false);
        tagGroup = new GridLayout(this);
        tagGroup.setColumnCount(2);
        tagGroup.setUseDefaultMargins(false);
        tagScroll.addView(tagGroup, new ScrollView.LayoutParams(-1, -2));
        tagContent.addView(tagScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        tagPanel.addView(tagContent);
        FrameLayout.LayoutParams tagPanelParams = new FrameLayout.LayoutParams(
                dp(240), dp(176), Gravity.BOTTOM | Gravity.END);
        tagPanelParams.setMargins(0, 0, dp(16), dp(78));
        overlayHost.addView(tagPanel, tagPanelParams);
        // The card background and drag strip move the panel; child checkboxes and the ScrollView
        // keep their own touch handling for selection and scrolling.
        tagPanel.setClickable(true);
        tagPanel.setOnTouchListener((view, event) -> handleTagPanelTouch(event));

        ViewCompat.setOnApplyWindowInsetsListener(overlayHost, (view, windowInsets) -> {
            int types = WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.navigationBars()
                    | WindowInsetsCompat.Type.statusBars();
            overlaySafeInsets = windowInsets.getInsetsIgnoringVisibility(types);
            applyOverlaySafeMargins();
            if (tagPanelPositioned) clampTagPanelPosition();
            return windowInsets;
        });

        root.addView(topBar, new LinearLayout.LayoutParams(-1, dp(56)));
        root.addView(imageHost, new LinearLayout.LayoutParams(-1, 0, 1));
        applyTopBarInsets(topBar);
        setContentView(overlayHost);
        ViewCompat.requestApplyInsets(overlayHost);
        showTopBarTemporarily();
    }

    private MaterialButton compactIconButton(int drawableRes, String description) {
        MaterialButton result = iconButton(drawableRes, description);
        result.setIconSize(dp(20));
        result.setMinWidth(dp(44));
        result.setMinimumWidth(dp(44));
        result.setMinHeight(dp(44));
        result.setMinimumHeight(dp(44));
        return result;
    }

    private void styleFullscreenActionButton(MaterialButton button, int iconColor) {
        button.setIconTint(ColorStateList.valueOf(iconColor));
        button.setBackgroundTintList(ColorStateList.valueOf(0xc01b211f));
        button.setStrokeColor(ColorStateList.valueOf(0x99000000));
        button.setStrokeWidth(dp(1));
        button.setCornerRadius(dp(5));
    }

    private void applyOverlaySafeMargins() {
        if (imageHost == null) return;
        if (progressHost != null && progressHost.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) progressHost.getLayoutParams();
            params.topMargin = Math.max(dp(8), overlaySafeInsets.top + dp(8));
            progressHost.setLayoutParams(params);
        }
        if (actionRow != null && actionRow.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) actionRow.getLayoutParams();
            params.rightMargin = Math.max(dp(16), overlaySafeInsets.right + dp(8));
            actionRow.setLayoutParams(params);
        }
    }

    private boolean handleTagPanelTouch(MotionEvent event) {
        if (tagPanel == null || overlayHost == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                draggingTagPanel = false;
                tagDragDownX = event.getRawX();
                tagDragDownY = event.getRawY();
                FrameLayout.LayoutParams start = tagPanelParams();
                // Bottom/end gravity leaves leftMargin/topMargin at zero even after the panel
                // has been laid out. Use the actual visible coordinates as the drag origin.
                tagDragStartLeft = tagPanel.getLeft();
                tagDragStartTop = tagPanel.getTop();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - tagDragDownX;
                float dy = event.getRawY() - tagDragDownY;
                if (!draggingTagPanel && Math.hypot(dx, dy) < dp(4)) return true;
                draggingTagPanel = true;
                FrameLayout.LayoutParams moving = tagPanelParams();
                moving.gravity = Gravity.TOP | Gravity.START;
                moving.leftMargin = tagDragStartLeft + Math.round(dx);
                moving.topMargin = tagDragStartTop + Math.round(dy);
                tagPanelLeft = moving.leftMargin;
                tagPanelTop = moving.topMargin;
                tagPanel.setLayoutParams(moving);
                clampTagPanelPosition();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (draggingTagPanel) {
                    int panelCenter = tagPanel.getLeft() + tagPanel.getWidth() / 2;
                    dockTagPanelSide(panelCenter >= overlayHost.getWidth() / 2);
                }
                draggingTagPanel = false;
                return true;
            default:
                return true;
        }
    }

    private FrameLayout.LayoutParams tagPanelParams() {
        ViewGroup.LayoutParams params = tagPanel.getLayoutParams();
        if (params instanceof FrameLayout.LayoutParams) {
            return (FrameLayout.LayoutParams) params;
        }
        return new FrameLayout.LayoutParams(tagPanel.getWidth(), tagPanel.getHeight());
    }

    private void dockTagPanel(boolean right) {
        if (tagPanel == null || overlayHost == null || tagPanel.getWidth() <= 0
                || tagPanel.getHeight() <= 0) return;
        tagPanelDockedRight = right;
        tagPanelPositioned = true;
        FrameLayout.LayoutParams params = tagPanelParams();
        params.gravity = Gravity.TOP | Gravity.START;
        int horizontal = right
                ? overlayHost.getWidth() - overlaySafeInsets.right - tagPanel.getWidth() - dp(12)
                : overlaySafeInsets.left + dp(12);
        int vertical = overlayHost.getHeight() - overlaySafeInsets.bottom
                - tagPanel.getHeight() - dp(84);
        params.leftMargin = horizontal;
        params.topMargin = vertical;
        tagPanelLeft = horizontal;
        tagPanelTop = vertical;
        tagPanel.setLayoutParams(params);
        clampTagPanelPosition();
    }

    /** Snap only horizontally after a drag; keep the user's chosen vertical position. */
    private void dockTagPanelSide(boolean right) {
        if (tagPanel == null || overlayHost == null || tagPanel.getWidth() <= 0
                || tagPanel.getHeight() <= 0) return;
        tagPanelDockedRight = right;
        tagPanelPositioned = true;
        FrameLayout.LayoutParams params = tagPanelParams();
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = right
                ? overlayHost.getWidth() - overlaySafeInsets.right - tagPanel.getWidth() - dp(12)
                : overlaySafeInsets.left + dp(12);
        tagPanelLeft = params.leftMargin;
        tagPanelTop = params.topMargin;
        tagPanel.setLayoutParams(params);
        clampTagPanelPosition();
    }

    private void clampTagPanelPosition() {
        if (tagPanel == null || overlayHost == null || tagPanel.getWidth() <= 0
                || tagPanel.getHeight() <= 0) return;
        FrameLayout.LayoutParams params = tagPanelParams();
        params.gravity = Gravity.TOP | Gravity.START;
        int minLeft = overlaySafeInsets.left + dp(8);
        int maxLeft = overlayHost.getWidth() - overlaySafeInsets.right
                - tagPanel.getWidth() - dp(8);
        int minTop = overlaySafeInsets.top + dp(8);
        int maxTop = overlayHost.getHeight() - overlaySafeInsets.bottom
                - tagPanel.getHeight() - dp(8);
        params.leftMargin = Math.max(minLeft, Math.min(maxLeft,
                tagPanelPositioned ? tagPanelLeft : params.leftMargin));
        params.topMargin = Math.max(minTop, Math.min(maxTop,
                tagPanelPositioned ? tagPanelTop : params.topMargin));
        tagPanelLeft = params.leftMargin;
        tagPanelTop = params.topMargin;
        tagPanel.setLayoutParams(params);
    }

    private void downloadCurrent() {
        if (actionBusy || index < 0 || index >= items.size()) return;
        setActionBusy(true);
        downloadCurrentData((ok, message, data) -> {
            if (!ok) {
                setActionBusy(false);
                showError(entries.get(index), "下载失败: " + message);
                return;
            }
            String name = items.get(index).name;
            try {
                imageExecutor.execute(() -> {
                    String savedLocation = "";
                    String error = "";
                    try {
                        savedLocation = com.openlistsorter.app.data.DownloadSaver.save(
                                this, name, data);
                    } catch (IOException | RuntimeException failure) {
                        error = "保存失败: " + failure.getMessage();
                    }
                    String finalLocation = savedLocation;
                    String finalError = error;
                    mainHandler.post(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        setActionBusy(false);
                        if (finalError.isEmpty()) titleView.setText("已保存到" + finalLocation + ": " + name);
                        else showError(entries.get(index), finalError);
                    });
                });
            } catch (RejectedExecutionException error) {
                setActionBusy(false);
                showError(entries.get(index), "后台保存任务已停止，请重试。");
            }
        });
    }

    private void convertCurrent() {
        if (actionBusy || index < 0 || index >= items.size()) return;
        Models.PreviewItem item = items.get(index);
        if (!item.tagsEditable) {
            showError(entries.get(index), "当前文件已经提交，不能重复转换。");
            return;
        }
        if (!Models.isImageFileName(item.name)) {
            showError(entries.get(index), "当前文件不是可转换的图片。");
            return;
        }
        new MaterialAlertDialogBuilder(this).setTitle("转换当前图片")
                .setMessage("转为 WebP 上传回原目录，并在成功后删除原文件。继续吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("转换", (dialog, which) -> {
                    setActionBusy(true);
                    downloadCurrentData((ok, message, data) -> {
                        if (!ok) {
                            setActionBusy(false);
                            showError(entries.get(index), "读取失败: " + message);
                            return;
                        }
                        try {
                            imageExecutor.execute(() -> {
                                ImageConverter.Result converted = ImageConverter.convertToWebp(
                                        data, item.name);
                                mainHandler.post(() -> {
                                    if (!converted.ok) {
                                        setActionBusy(false);
                                        showError(entries.get(index), converted.message);
                                        return;
                                    }
                                    String target = Models.joinRemotePath(
                                            Models.parentRemotePath(item.path), converted.outputName);
                                    client.uploadFile(target, converted.data, false, (uploadOk, uploadMessage) -> {
                                        if (!uploadOk) {
                                            setActionBusy(false);
                                            showError(entries.get(index), "上传 WebP 失败: " + uploadMessage);
                                            return;
                                        }
                                        client.removeFile(Models.parentRemotePath(item.path), item.name,
                                                (removeOk, removeMessage) -> {
                                                    setActionBusy(false);
                                                    if (!removeOk) {
                                                        showError(entries.get(index), "转换完成，但删除原文件失败: " + removeMessage);
                                                        return;
                                                    }
                                                    item.removed = true;
                                                    persistSession();
                                                    render();
                                                });
                                    });
                                });
                            });
                        } catch (RejectedExecutionException error) {
                            setActionBusy(false);
                            showError(entries.get(index), "转换任务已停止。");
                        }
                    });
                }).show();
    }

    private void deleteCurrent() {
        if (actionBusy || index < 0 || index >= items.size()) return;
        Models.PreviewItem item = items.get(index);
        if (!item.tagsEditable) {
            showError(entries.get(index), "当前文件已经提交，不能删除。");
            return;
        }
        new MaterialAlertDialogBuilder(this).setTitle("删除当前文件")
                .setMessage("此操作会直接删除远程文件，无法撤销。\n\n" + item.name)
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    setActionBusy(true);
                    client.removeFile(Models.parentRemotePath(item.path), item.name, (ok, message) -> {
                        setActionBusy(false);
                        if (!ok) {
                            showError(entries.get(index), "删除失败: " + message);
                            return;
                        }
                        item.removed = true;
                        boolean remaining = false;
                        for (Models.PreviewItem candidate : items) {
                            if (!candidate.removed) {
                                remaining = true;
                                break;
                            }
                        }
                        if (!remaining) {
                            finish();
                            return;
                        }
                        index = Math.min(index, items.size() - 1);
                        while (index < items.size() && items.get(index).removed) index++;
                        if (index >= items.size()) {
                            index = items.size() - 1;
                            while (index >= 0 && items.get(index).removed) index--;
                        }
                        persistSession();
                        render();
                    });
                }).show();
    }

    private interface DataCallback {
        void onResult(boolean ok, String message, byte[] data);
    }

    private void downloadCurrentData(DataCallback callback) {
        Models.PreviewItem item = items.get(index);
        java.net.URL url = OpenListClient.resolveUrl(client.getBaseUrl(), item.rawUrl);
        if (url == null) {
            client.getFileInfo(item.path, (ok, message, fetched) -> {
                if (!ok || fetched.rawUrl.isEmpty()) {
                    callback.onResult(false, ok ? "OpenList 没有返回下载地址。" : message, new byte[0]);
                    return;
                }
                item.rawUrl = fetched.rawUrl;
                entries.get(index).rawUrl = fetched.rawUrl;
                downloadCurrentData(callback);
            });
            return;
        }
        client.downloadUrl(url, callback::onResult, null);
    }

    private void setActionBusy(boolean busy) {
        actionBusy = busy;
        if (index >= 0 && index < items.size()) updateActionState(items.get(index));
        if (prevButton != null) prevButton.setEnabled(!busy && index > 0);
        if (nextButton != null) nextButton.setEnabled(!busy && index >= 0
                && index < items.size() - 1);
        if (actionPreviousButton != null) actionPreviousButton.setEnabled(!busy && index > 0);
        if (actionNextButton != null) actionNextButton.setEnabled(!busy && index >= 0
                && index < items.size() - 1);
    }

    private void updateActionState(Models.PreviewItem item) {
        if (downloadAction != null) downloadAction.setEnabled(!actionBusy && !item.removed);
        if (convertAction != null) {
            convertAction.setEnabled(!actionBusy && !item.removed && item.tagsEditable
                    && Models.isImageFileName(item.name));
        }
        if (deleteAction != null) {
            deleteAction.setEnabled(!actionBusy && !item.removed && item.tagsEditable);
        }
    }

    private void render() {
        if (exiting || resourcesReleased) return;
        if (items.isEmpty()) {
            titleView.setText("没有可查看的文件");
            counterView.setText("0 / 0");
            prevButton.setEnabled(false);
            nextButton.setEnabled(false);
            if (actionPreviousButton != null) actionPreviousButton.setEnabled(false);
            if (actionNextButton != null) actionNextButton.setEnabled(false);
            return;
        }
        if (items.get(index).removed) {
            int active = index;
            while (active < items.size() && items.get(active).removed) active++;
            if (active >= items.size()) {
                active = index - 1;
                while (active >= 0 && items.get(active).removed) active--;
            }
            if (active < 0) {
                finish();
                return;
            }
            index = active;
        }
        Models.PreviewItem item = items.get(index);
        renderTags(item);
        updateActionState(item);
        clearError();
        titleView.setText(item.name);
        counterView.setText((index + 1) + " / " + items.size());
        prevButton.setEnabled(index > 0);
        nextButton.setEnabled(index < items.size() - 1);
        if (actionPreviousButton != null) actionPreviousButton.setEnabled(index > 0 && !actionBusy);
        if (actionNextButton != null) actionNextButton.setEnabled(index < items.size() - 1 && !actionBusy);
        imageView.setImageDrawable(null);
        imageView.setVisibility(View.GONE);
        releaseVideoPlayer();
        videoView.setVisibility(View.GONE);
        if (item.isVideo) {
            videoView.setVisibility(View.VISIBLE);
            videoRetryTarget = -1;
            videoRetrying = false;
            prepareVideo(index);
        } else {
            loadImage(index);
        }
        prefetchNextImages();
    }

    private void renderTags(Models.PreviewItem item) {
        if (tagGroup == null) return;
        if (tagNames.isEmpty()) {
            if (tagGroup.getChildCount() != 1
                    || !(tagGroup.getChildAt(0) instanceof TextView)) {
                tagGroup.removeAllViews();
                TextView empty = new TextView(this);
                empty.setText("暂无标签，请先在主页面新增");
                empty.setTextColor(0xffcfd8d5);
                empty.setTextSize(13);
                tagGroup.addView(empty);
            }
            return;
        }
        ensureTagRows();
        updatingTagChecks = true;
        for (int i = 0; i < tagNames.size(); i++) {
            String name = tagNames.get(i);
            MaterialCheckBox check = (MaterialCheckBox) tagGroup.getChildAt(i);
            check.setEnabled(item.tagsEditable);
            check.setChecked(item.selectedTags.contains(name));
        }
        updatingTagChecks = false;
        tagPanel.setContentDescription(item.tagsEditable
                ? "当前文件标签列表" : "当前文件已提交，标签只读");
    }

    private void ensureTagRows() {
        boolean reusable = tagGroup.getChildCount() == tagNames.size();
        if (reusable) {
            for (int i = 0; i < tagNames.size(); i++) {
                View row = tagGroup.getChildAt(i);
                if (!(row instanceof MaterialCheckBox)
                        || !tagNames.get(i).equals(row.getTag())) {
                    reusable = false;
                    break;
                }
            }
        }
        if (reusable) return;
        tagGroup.removeAllViews();
        for (String name : tagNames) {
            MaterialCheckBox check = new MaterialCheckBox(this);
            check.setText(name);
            check.setTag(name);
            check.setTextColor(Color.WHITE);
            check.setTextSize(13);
            check.setGravity(Gravity.CENTER_VERTICAL);
            check.setMinHeight(dp(44));
            check.setPadding(0, 0, dp(4), 0);
            check.setSingleLine(true);
            check.setEllipsize(android.text.TextUtils.TruncateAt.END);
            check.setOnCheckedChangeListener((button, checked) -> {
                if (updatingTagChecks || index < 0 || index >= items.size()) return;
                Models.PreviewItem current = items.get(index);
                if (!current.tagsEditable) return;
                String selectedName = (String) button.getTag();
                if (checked) {
                    if (!current.selectedTags.contains(selectedName)) {
                        current.selectedTags.add(selectedName);
                    }
                } else {
                    current.selectedTags.remove(selectedName);
                }
                persistSession();
            });
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f));
            params.width = 0;
            params.height = dp(44);
            params.setMargins(dp(2), 0, dp(2), 0);
            tagGroup.addView(check, params);
        }
    }

    /** Warm the shared raw cache for the next images without affecting the visible loading state. */
    private void prefetchNextImages() {
        if (exiting || resourcesReleased || items.isEmpty()) return;
        int started = 0;
        for (int target = index + 1; target < items.size()
                && started < preloadConcurrency; target++) {
            Models.PreviewItem item = items.get(target);
            if (item.removed || item.isVideo) continue;
            Models.RemoteEntry entry = entries.get(target);
            String key = prefetchKey(entry);
            if (prefetchInFlight.contains(key)
                    || previewCache.hasRawData(entry)
                    || previewCache.hasImage(entry)) {
                continue;
            }
            prefetchInFlight.add(key);
            started++;
            prefetchItem(target, entry, key);
        }
    }

    private void prefetchItem(int target, Models.RemoteEntry entry, String key) {
        if (entry.rawUrl == null || entry.rawUrl.isEmpty()) {
            client.getFileInfo(entry.path, (ok, message, fetched) -> {
                if (!ok || fetched.rawUrl == null || fetched.rawUrl.isEmpty()) {
                    prefetchInFlight.remove(key);
                    return;
                }
                entry.rawUrl = fetched.rawUrl;
                if (target >= 0 && target < items.size()) items.get(target).rawUrl = fetched.rawUrl;
                prefetchDownload(entry, key);
            });
            return;
        }
        prefetchDownload(entry, key);
    }

    private void prefetchDownload(Models.RemoteEntry entry, String key) {
        java.net.URL url = OpenListClient.resolveUrl(client.getBaseUrl(), entry.rawUrl);
        if (url == null) {
            prefetchInFlight.remove(key);
            return;
        }
        client.downloadUrl(url, (ok, message, data) -> {
            if (!ok || data == null || data.length == 0) {
                prefetchInFlight.remove(key);
                return;
            }
            try {
                prefetchWriteExecutor.execute(() -> {
                    previewCache.storeRawData(entry, data);
                    mainHandler.post(() -> prefetchInFlight.remove(key));
                });
            } catch (RejectedExecutionException error) {
                prefetchInFlight.remove(key);
            }
        }, null, true);
    }

    private static String prefetchKey(Models.RemoteEntry entry) {
        return entry.path + "\n" + entry.size + "\n" + entry.modified;
    }

    private void loadImage(int target) {
        if (exiting || resourcesReleased) return;
        cancelQueuedImageWork();
        final int generation = ++loadingGeneration;
        final Models.RemoteEntry entry = entries.get(target);
        if (index != target) return;
        progressHost.setVisibility(View.VISIBLE);
        try {
            imageExecutor.execute(() -> {
                Bitmap cached = previewCache.loadImage(
                        entry, previewDecodeLimit, previewDecodeLimit);
                if (exiting || resourcesReleased) {
                    return;
                }
                mainHandler.post(() -> {
                    if (exiting || resourcesReleased
                            || index != target || generation != loadingGeneration) return;
                    if (cached != null) {
                        showImage(cached);
                    } else {
                        fetchThenDecode(target, generation);
                    }
                });
            });
        } catch (RejectedExecutionException error) {
            progressHost.setVisibility(View.GONE);
            showError(entry, "图片任务已停止。");
        }
    }

    private void fetchThenDecode(int target, int generation) {
        if (exiting || resourcesReleased) return;
        final Models.RemoteEntry entry = entries.get(target);
        try {
            imageExecutor.execute(() -> {
                byte[] cachedRaw = previewCache.loadRawData(entry);
                mainHandler.post(() -> {
                    if (exiting || resourcesReleased
                            || index != target || generation != loadingGeneration) return;
                    if (cachedRaw != null) {
                        decode(target, generation, cachedRaw, false);
                    } else if (entry.rawUrl.isEmpty()) {
                        client.getFileInfo(entry.path, (ok, message, fetched) -> {
                            if (exiting || resourcesReleased
                                    || index != target || generation != loadingGeneration) return;
                            if (!ok || fetched.rawUrl.isEmpty()) {
                                progressHost.setVisibility(View.GONE);
                                showError(entry, ok ? "OpenList 没有返回下载地址。" : message);
                                return;
                            }
                            entry.rawUrl = fetched.rawUrl;
                            entry.thumb = fetched.thumb;
                            items.get(target).rawUrl = fetched.rawUrl;
                            downloadThenDecode(target, generation);
                        });
                    } else {
                        downloadThenDecode(target, generation);
                    }
                });
            });
        } catch (RejectedExecutionException error) {
            if (index == target && generation == loadingGeneration) {
                progressHost.setVisibility(View.GONE);
            }
        }
    }

    private void downloadThenDecode(int target, int generation) {
        if (exiting || resourcesReleased) return;
        final Models.RemoteEntry entry = entries.get(target);
        java.net.URL url = OpenListClient.resolveUrl(client.getBaseUrl(), entry.rawUrl);
        if (url == null) {
            if (index == target && generation == loadingGeneration) {
                progressHost.setVisibility(View.GONE);
                showError(entry, "下载地址无效。");
            }
            return;
        }
        client.downloadUrl(url, (ok, message, data) -> {
            if (exiting || resourcesReleased
                    || index != target || generation != loadingGeneration) return;
            if (!ok) {
                progressHost.setVisibility(View.GONE);
                showError(entry, "读取失败: " + message);
                return;
            }
            decode(target, generation, data, true);
        }, null);
    }

    private void decode(int target, int generation, byte[] data, boolean storeRawData) {
        if (exiting || resourcesReleased) return;
        final Models.RemoteEntry entry = entries.get(target);
        try {
            imageExecutor.execute(() -> {
                if (storeRawData) previewCache.storeRawData(entry, data);
                if (exiting || resourcesReleased
                        || index != target || generation != loadingGeneration) return;
                Bitmap image = ImageConverter.decodePreview(data,
                        previewDecodeLimit, previewDecodeLimit,
                        () -> !exiting && !resourcesReleased && index == target
                                && generation == loadingGeneration);
                if (exiting || resourcesReleased
                        || index != target || generation != loadingGeneration) {
                    if (image != null && !image.isRecycled()) image.recycle();
                    return;
                }
                mainHandler.post(() -> {
                    if (exiting || resourcesReleased
                            || index != target || generation != loadingGeneration) {
                        // This bitmap was never assigned to the ImageView and is not in the
                        // cache, so it is safe to release the native buffer immediately.
                        if (image != null && !image.isRecycled()) image.recycle();
                        return;
                    }
                    progressHost.setVisibility(View.GONE);
                    if (image == null) {
                        showError(entry, "系统图片解码器不支持此格式。");
                    } else {
                        previewCache.putImageInMemory(entry, image);
                        showImage(image);
                    }
                });
            });
        } catch (RejectedExecutionException error) {
            if (index == target && generation == loadingGeneration) {
                progressHost.setVisibility(View.GONE);
                showError(entry, "图片解码任务已停止。");
            }
        }
    }

    private void showImage(Bitmap image) {
        progressHost.setVisibility(View.GONE);
        clearError();
        imageView.setImageBitmap(image);
        imageView.setVisibility(View.VISIBLE);
        imageView.resetView();
    }

    private void showError(Models.RemoteEntry entry, String message) {
        progressHost.setVisibility(View.GONE);
        clearError();
        TextView error = new TextView(this);
        error.setText(entry.name + "\n\n" + message);
        error.setTextColor(0xffcfd8d5);
        error.setTextSize(14);
        error.setGravity(Gravity.CENTER);
        error.setPadding(dp(24), dp(24), dp(24), dp(24));
        errorView = error;
        imageView.setImageDrawable(null);
        videoView.setVisibility(View.GONE);
        FrameLayout host = (FrameLayout) imageView.getParent();
        host.addView(error, new FrameLayout.LayoutParams(-1, -1));
    }

    private void clearError() {
        if (errorView != null && errorView.getParent() instanceof View) {
            ((FrameLayout) errorView.getParent()).removeView(errorView);
        }
        errorView = null;
    }

    private void prepareVideo(int target) {
        if (exiting || resourcesReleased || target != index) return;
        Models.RemoteEntry entry = entries.get(index);
        java.net.URL url = OpenListClient.resolveUrl(client.getBaseUrl(), entry.rawUrl);
        if (url == null) {
            progressHost.setVisibility(View.VISIBLE);
            client.getFileInfo(entry.path, (ok, message, fetched) -> {
                progressHost.setVisibility(View.GONE);
                if (ok && !fetched.rawUrl.isEmpty() && target == index) {
                    entry.rawUrl = fetched.rawUrl;
                    items.get(index).rawUrl = fetched.rawUrl;
                    java.net.URL resolved = OpenListClient.resolveUrl(client.getBaseUrl(), fetched.rawUrl);
                    if (resolved != null) startVideoPlayback(target, entry, resolved);
                } else if (titleView != null) {
                    titleView.setText(ok ? "OpenList 没有返回视频地址" : message);
                }
            });
            return;
        }
        startVideoPlayback(target, entry, url);
    }

    private void startVideoPlayback(int target, Models.RemoteEntry entry, java.net.URL url) {
        if (target != index || exiting || resourcesReleased) return;
        releaseVideoPlayer();
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(30_000)
                .setAllowCrossProtocolRedirects(true);
        java.util.HashMap<String, String> requestHeaders = new java.util.HashMap<>();
        requestHeaders.put("Accept", "video/*,application/octet-stream;q=0.9,*/*;q=0.5");
        requestHeaders.put("User-Agent", "OpenListSorter/1.0");
        String token = sessionToken;
        if (token.isEmpty()) token = ConnectionStore.load(this).token;
        if (!token.isEmpty() && sameOrigin(url, client.getBaseUrl())) {
            requestHeaders.put("Authorization", token);
        }
        httpFactory.setDefaultRequestProperties(requestHeaders);
        ExoPlayer player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(httpFactory))
                .build();
        videoPlayer = player;
        videoView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (target != index) return;
                if (playbackState == Player.STATE_READY) progressHost.setVisibility(View.GONE);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (target != index) return;
                progressHost.setVisibility(View.GONE);
                if (!videoRetrying && videoRetryTarget != target) {
                    videoRetryTarget = target;
                    retryVideoUrl(target, entry);
                    return;
                }
                Throwable cause = error;
                while (cause.getCause() != null) cause = cause.getCause();
                String detail = error.getErrorCodeName();
                if (cause.getMessage() != null && !cause.getMessage().isEmpty()) {
                    detail += "\n" + cause.getMessage();
                }
                showError(entry, "视频解码或读取失败: " + detail);
            }
        });
        MediaItem.Builder mediaItem = new MediaItem.Builder().setUri(url.toString());
        String mimeType = Models.videoMimeType(entry.name);
        if (!mimeType.isEmpty()) mediaItem.setMimeType(mimeType);
        progressHost.setVisibility(View.VISIBLE);
        player.setMediaItem(mediaItem.build());
        player.prepare();
        player.play();
    }

    private void retryVideoUrl(int target, Models.RemoteEntry entry) {
        videoRetrying = true;
        progressHost.setVisibility(View.VISIBLE);
        client.getFileInfo(entry.path, (ok, message, fetched) -> {
            if (exiting || resourcesReleased || target != index) return;
            videoRetrying = false;
            if (!ok || fetched.rawUrl == null || fetched.rawUrl.isEmpty()) {
                progressHost.setVisibility(View.GONE);
                showError(entry, "视频地址刷新失败: " + message);
                return;
            }
            entry.rawUrl = fetched.rawUrl;
            items.get(target).rawUrl = fetched.rawUrl;
            java.net.URL freshUrl = OpenListClient.resolveUrl(client.getBaseUrl(), fetched.rawUrl);
            if (freshUrl == null) {
                progressHost.setVisibility(View.GONE);
                showError(entry, "OpenList 返回的视频地址无效。");
                return;
            }
            startVideoPlayback(target, entry, freshUrl);
        });
    }

    private boolean sameOrigin(java.net.URL url, String baseUrl) {
        try {
            java.net.URI left = url.toURI();
            java.net.URI right = new java.net.URI(baseUrl);
            int leftPort = left.getPort() >= 0 ? left.getPort() : defaultPort(left.getScheme());
            int rightPort = right.getPort() >= 0 ? right.getPort() : defaultPort(right.getScheme());
            return left.getScheme().equalsIgnoreCase(right.getScheme())
                    && left.getHost().equalsIgnoreCase(right.getHost())
                    && leftPort == rightPort;
        } catch (Exception ignored) {
            return false;
        }
    }

    private int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private void releaseVideoPlayer() {
        if (videoPlayer == null) return;
        videoView.setPlayer(null);
        videoPlayer.release();
        videoPlayer = null;
    }

    private void move(int delta) {
        if (exiting || actionBusy || items.isEmpty()) return;
        int target = index + delta;
        while (target >= 0 && target < items.size() && items.get(target).removed) target += delta;
        if (target < 0 || target >= items.size()) return;
        index = target;
        persistSession();
        render();
    }

    private void persistSession() {
        if (sessionId == null || sessionId.isEmpty()) return;
        PreviewSessionStore.Session session = PreviewSessionStore.peek(sessionId);
        if (session == null) return;
        session.index = index;
        // The session object is already the live in-memory source of truth. Coalesce disk writes
        // while users mark a batch of images; serializing the entire list for every checkbox can
        // otherwise create a large burst of Parcel allocations on the UI thread.
        mainHandler.removeCallbacks(persistRunnable);
        mainHandler.postDelayed(persistRunnable, 350L);
    }

    private void persistSessionNow() {
        if (sessionId == null || sessionId.isEmpty()) return;
        PreviewSessionStore.Session session = PreviewSessionStore.peek(sessionId);
        if (session == null) return;
        session.index = index;
        PreviewSessionStore.update(sessionId, session);
    }

    private void cancelQueuedImageWork() {
        if (imageExecutor != null) imageExecutor.getQueue().clear();
    }

    private void releaseImageResources() {
        if (resourcesReleased) return;
        resourcesReleased = true;
        ++loadingGeneration;
        mainHandler.removeCallbacksAndMessages(null);
        if (imageExecutor != null) {
            imageExecutor.getQueue().clear();
            // Let an in-progress native decode return normally. Interrupting libheif while the
            // Activity is in its exit transition can terminate the process on some devices.
            PreviewCache cacheToRelease = previewCache;
            try {
                imageExecutor.execute(() -> {
                    try {
                        Thread.sleep(DETACHED_BITMAP_RECYCLE_DELAY_MS);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                    if (cacheToRelease != null) cacheToRelease.clearMemoryAndRecycle();
                });
            } catch (RejectedExecutionException error) {
                if (cacheToRelease != null) cacheToRelease.clearMemory();
            }
            imageExecutor.shutdown();
        }
        if (prefetchWriteExecutor != null) {
            prefetchWriteExecutor.shutdownNow();
        }
        if (client != null) {
            client.shutdown();
            client = null;
        }
        // The window is being destroyed now, so drop the last drawable references before
        // releasing the cache. This prevents a full-size decoded image from surviving the
        // transition back to MainActivity.
        if (imageView != null) imageView.setImageDrawable(null);
        releaseVideoPlayer();
        // The executor's final task recycles the detached cache after any native decode returns
        // and after the renderer has had a short grace period to finish the exit transition.
    }

    @Override
    public void finish() {
        if (resultSent) {
            super.finish();
            return;
        }
        resultSent = true;
        exiting = true;
        ++loadingGeneration;
        cancelQueuedImageWork();
        if (progressHost != null) progressHost.setVisibility(View.GONE);
        if (imageView != null) imageView.setImageDrawable(null);
        releaseVideoPlayer();
        mainHandler.removeCallbacks(persistRunnable);
        persistSessionNow();
        Intent result = new Intent();
        PreviewSessionStore.Session session = PreviewSessionStore.peek(sessionId);
        if (sessionId != null && !sessionId.isEmpty()) {
            if (session != null) session.index = index;
            // A session id is always safe to return through Binder. Never fall back to the full
            // item/tag array for a normal launch, even if the process-local store was evicted.
            result.putExtra(EXTRA_SESSION_ID, sessionId);
        } else {
            // Compatibility path for direct launches/tests. Normal app navigation uses a
            // session id and therefore never places the large item list in a Binder result.
            result.putParcelableArrayListExtra(EXTRA_RESULT_ITEMS, items);
            if (index >= 0 && index < items.size()) {
                result.putExtra(EXTRA_RESULT_PATH, items.get(index).path);
            }
        }
        setResult(RESULT_OK, result);
        // Do not tear down views or interrupt native decoders before the window transition has
        // completed. onDestroy() performs the non-visual cleanup after the result is delivered.
        super.finish();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_TAG_PANEL_POSITIONED, tagPanelPositioned);
        outState.putBoolean(STATE_TAG_PANEL_RIGHT, tagPanelDockedRight);
        outState.putInt(STATE_TAG_PANEL_LEFT, tagPanelLeft);
        outState.putInt(STATE_TAG_PANEL_TOP, tagPanelTop);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(hideTopBarRunnable);
        releaseImageResources();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        if (videoPlayer != null) videoPlayer.pause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (videoPlayer != null && videoView.getVisibility() == View.VISIBLE) {
            videoPlayer.play();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideStatusBar();
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

    private void showTopBarTemporarily() {
        if (topBar == null) return;
        mainHandler.removeCallbacks(hideTopBarRunnable);
        if (topBar.getVisibility() != View.VISIBLE) {
            topBar.setAlpha(0f);
            topBar.setVisibility(View.VISIBLE);
            topBar.animate().alpha(1f).setDuration(140L).start();
        } else {
            topBar.setAlpha(1f);
        }
        mainHandler.postDelayed(hideTopBarRunnable, TOP_BAR_HIDE_DELAY_MS);
    }

    private void toggleTopBar() {
        if (topBar == null) return;
        mainHandler.removeCallbacks(hideTopBarRunnable);
        if (topBar.getVisibility() == View.VISIBLE && topBar.getAlpha() > 0f) {
            hideTopBar();
        } else {
            showTopBarTemporarily();
        }
    }

    private void hideTopBar() {
        if (topBar == null || isFinishing() || isDestroyed()) return;
        topBar.animate().alpha(0f).setDuration(180L).withEndAction(() -> {
            if (topBar != null) topBar.setVisibility(View.GONE);
        }).start();
    }

    private void hideStatusBar() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .hide(WindowInsetsCompat.Type.statusBars());
    }

    private void applyTopBarInsets(View view) {
        final int baseHeight = dp(56);
        ViewCompat.setOnApplyWindowInsetsListener(view, (target, windowInsets) -> {
            Insets safe = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout());
            // Status bars are hidden in this activity. Only reserve a real display cutout;
            // treating a transient/status inset as padding creates a large blank band above the bar.
            int topInset = safe.top;
            target.setPadding(safe.left, topInset, safe.right, 0);
            ViewGroup.LayoutParams params = target.getLayoutParams();
            if (params != null) {
                params.height = baseHeight + topInset;
                target.setLayoutParams(params);
            }
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    private MaterialButton iconButton(int drawableRes, String description) {
        MaterialButton result = new MaterialButton(this);
        result.setIconResource(drawableRes);
        result.setIconTint(ColorStateList.valueOf(Color.WHITE));
        result.setIconSize(dp(22));
        result.setIconPadding(0);
        result.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_TOP);
        result.setGravity(Gravity.CENTER);
        result.setPadding(0, 0, 0, 0);
        result.setInsetTop(0);
        result.setInsetBottom(0);
        result.setMinWidth(0);
        result.setMinimumWidth(0);
        result.setMinHeight(0);
        result.setMinimumHeight(0);
        result.setContentDescription(description);
        result.setCornerRadius(0);
        result.setStrokeWidth(0);
        result.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        result.setStrokeColor(ColorStateList.valueOf(Color.TRANSPARENT));
        result.setElevation(0f);
        return result;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
