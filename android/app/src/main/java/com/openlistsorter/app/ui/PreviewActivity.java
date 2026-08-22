package com.openlistsorter.app.ui;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.OutputStream;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.openlistsorter.app.R;
import com.openlistsorter.app.data.ConnectionProfile;
import com.openlistsorter.app.data.ConnectionStore;
import com.openlistsorter.app.data.ImageConverter;
import com.openlistsorter.app.data.Models;
import com.openlistsorter.app.data.PreviewCache;
import com.openlistsorter.app.network.OpenListClient;

import java.util.ArrayList;
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
    public static final String EXTRA_RESULT_ITEMS = "result_items";
    public static final String EXTRA_RESULT_PATH = "result_path";
    private static final int REQUEST_CREATE_DOCUMENT = 3101;
    private static final String STATE_TAG_PANEL_POSITIONED = "tag_panel_positioned";
    private static final String STATE_TAG_PANEL_RIGHT = "tag_panel_right";
    private static final String STATE_TAG_PANEL_LEFT = "tag_panel_left";
    private static final String STATE_TAG_PANEL_TOP = "tag_panel_top";
    private static final long DETACHED_BITMAP_RECYCLE_DELAY_MS = 500L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private OpenListClient client;
    private PreviewCache previewCache;
    private ThreadPoolExecutor imageExecutor;

    private ArrayList<Models.PreviewItem> items = new ArrayList<>();
    private final ArrayList<Models.RemoteEntry> entries = new ArrayList<>();
    private volatile int index;

    private TextView titleView;
    private TextView counterView;
    private MaterialButton prevButton;
    private MaterialButton nextButton;
    private ZoomImageView imageView;
    private ImageView videoView;
    private LinearLayout progressHost;
    private TextView errorView;
    private MaterialButton tagToggle;
    private MaterialCardView tagPanel;
    private LinearLayout tagGroup;
    private MaterialButton downloadAction;
    private MaterialButton convertAction;
    private MaterialButton deleteAction;
    private FrameLayout overlayHost;
    private FrameLayout imageHost;
    private View actionRow;
    private ArrayList<String> tagNames = new ArrayList<>();
    private volatile int loadingGeneration;
    private String sessionId;
    private int previewDecodeLimit = 1536;
    private volatile boolean resourcesReleased;
    private volatile boolean exiting;
    private boolean updatingTagChecks;
    private boolean resultSent;
    private boolean actionBusy;
    private byte[] pendingDownloadData;
    private String pendingDownloadName;
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
        client = new OpenListClient();
        previewCache = new PreviewCache(this);
        ConnectionProfile profile = ConnectionStore.load(this);
        client.setBaseUrl(profile.endpoint);
        client.setToken(profile.token);

        sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
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

        LinearLayout topBar = new LinearLayout(this);
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

        imageHost = new FrameLayout(this);
        imageHost.setBackgroundColor(0xff101413);

        imageView = new ZoomImageView(this);
        imageView.setBackgroundColor(0xff101413);
        imageView.setContentDescription("全屏图片预览，双指缩放，拖动查看，双击复位");
        imageHost.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        videoView = new ImageView(this);
        videoView.setImageResource(android.R.drawable.ic_media_play);
        videoView.setBackgroundColor(0x1a000000);
        videoView.setContentDescription("在外部播放器中打开视频");
        videoView.setVisibility(View.GONE);
        videoView.setOnClickListener(v -> openVideo());
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

        tagToggle = iconButton(android.R.drawable.ic_menu_edit, "展开标签选择");
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
        FrameLayout.LayoutParams tagToggleParams = new FrameLayout.LayoutParams(
                dp(52), dp(52), Gravity.BOTTOM | Gravity.END);
        tagToggleParams.setMargins(0, 0, dp(16), dp(20));
        imageHost.addView(tagToggle, tagToggleParams);

        LinearLayout actionRow = new LinearLayout(this);
        this.actionRow = actionRow;
        actionRow.setOrientation(LinearLayout.VERTICAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setPadding(0, dp(2), 0, dp(2));
        downloadAction = compactIconButton(android.R.drawable.ic_menu_save, "下载当前文件");
        convertAction = compactIconButton(android.R.drawable.ic_menu_manage, "转换当前图片为 WebP");
        deleteAction = compactIconButton(android.R.drawable.ic_menu_delete, "删除当前文件");
        styleFullscreenActionButton(downloadAction, Color.WHITE);
        styleFullscreenActionButton(convertAction, Color.WHITE);
        styleFullscreenActionButton(deleteAction, 0xffff8a80);
        downloadAction.setOnClickListener(v -> downloadCurrent());
        convertAction.setOnClickListener(v -> convertCurrent());
        deleteAction.setOnClickListener(v -> deleteCurrent());
        actionRow.addView(downloadAction, new LinearLayout.LayoutParams(dp(36), dp(36)));
        actionRow.addView(convertAction, new LinearLayout.LayoutParams(dp(36), dp(36)));
        actionRow.addView(deleteAction, new LinearLayout.LayoutParams(dp(36), dp(36)));
        FrameLayout.LayoutParams actionParams = new FrameLayout.LayoutParams(
                dp(36), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.END);
        actionParams.setMargins(0, 0, dp(16), 0);
        imageHost.addView(actionRow, actionParams);

        tagPanel = new MaterialCardView(this);
        tagPanel.setCardBackgroundColor(0xf21b211f);
        tagPanel.setRadius(dp(10));
        tagPanel.setVisibility(View.GONE);
        LinearLayout tagContent = new LinearLayout(this);
        tagContent.setOrientation(LinearLayout.VERTICAL);
        tagContent.setPadding(dp(10), dp(2), dp(10), dp(4));
        View tagDragHandle = new View(this);
        tagDragHandle.setContentDescription("拖动标签面板");
        tagDragHandle.setBackgroundColor(0x33111111);
        tagDragHandle.setOnTouchListener((view, event) -> handleTagPanelTouch(event));
        tagContent.addView(tagDragHandle, new LinearLayout.LayoutParams(-1, dp(32)));
        ScrollView tagScroll = new ScrollView(this);
        tagScroll.setClipToPadding(false);
        tagGroup = new LinearLayout(this);
        tagGroup.setOrientation(LinearLayout.VERTICAL);
        tagScroll.addView(tagGroup, new ScrollView.LayoutParams(-1, -2));
        tagContent.addView(tagScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        tagPanel.addView(tagContent);
        FrameLayout.LayoutParams tagPanelParams = new FrameLayout.LayoutParams(
                dp(280), dp(224), Gravity.BOTTOM | Gravity.END);
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

        GestureDetector fling = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                        if (Math.abs(vx) < Math.abs(vy) || Math.abs(vx) < dp(120)) return false;
                        move(vx < 0 ? 1 : -1);
                        return true;
                    }
                });
        imageView.setSwipeListener(new ZoomImageView.SwipeListener() {
            @Override
            public void onSwipeLeft() {
                move(1);
            }

            @Override
            public void onSwipeRight() {
                move(-1);
            }
        });
        videoView.setOnTouchListener((v, event) -> fling.onTouchEvent(event));

        root.addView(topBar, new LinearLayout.LayoutParams(-1, dp(56)));
        root.addView(imageHost, new LinearLayout.LayoutParams(-1, 0, 1));
        applyTopBarInsets(topBar);
        setContentView(overlayHost);
        ViewCompat.requestApplyInsets(overlayHost);
    }

    private MaterialButton compactIconButton(int drawableRes, String description) {
        MaterialButton result = iconButton(drawableRes, description);
        result.setIconSize(dp(18));
        result.setMinWidth(dp(38));
        result.setMinimumWidth(dp(38));
        result.setMinHeight(dp(38));
        result.setMinimumHeight(dp(38));
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
        if (tagToggle != null && tagToggle.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) tagToggle.getLayoutParams();
            params.rightMargin = Math.max(dp(16), overlaySafeInsets.right + dp(8));
            params.bottomMargin = Math.max(dp(20), overlaySafeInsets.bottom + dp(12));
            tagToggle.setLayoutParams(params);
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
                tagDragStartLeft = start.leftMargin;
                tagDragStartTop = start.topMargin;
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
                    dockTagPanel(panelCenter >= overlayHost.getWidth() / 2);
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
            setActionBusy(false);
            if (!ok) {
                showError(entries.get(index), "下载失败: " + message);
                return;
            }
            pendingDownloadData = data;
            pendingDownloadName = items.get(index).name;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE, pendingDownloadName);
            try {
                startActivityForResult(intent, REQUEST_CREATE_DOCUMENT);
            } catch (ActivityNotFoundException error) {
                pendingDownloadData = null;
                pendingDownloadName = null;
                showError(entries.get(index), "系统没有可用的文件保存界面。");
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CREATE_DOCUMENT) return;
        byte[] bytes = pendingDownloadData;
        String name = pendingDownloadName;
        pendingDownloadData = null;
        pendingDownloadName = null;
        setActionBusy(false);
        if (resultCode != RESULT_OK || data == null || data.getData() == null || bytes == null) {
            return;
        }
        Uri target = data.getData();
        try {
            imageExecutor.execute(() -> {
                String error = "";
                try (OutputStream output = getContentResolver().openOutputStream(target, "w")) {
                    if (output == null) error = "无法打开用户选择的保存位置。";
                    else {
                        output.write(bytes);
                        output.flush();
                    }
                } catch (IOException | RuntimeException failure) {
                    error = "下载保存失败: " + failure.getMessage();
                }
                String finalError = error;
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (finalError.isEmpty()) titleView.setText("已下载: " + name);
                    else showError(entries.get(index), finalError);
                });
            });
        } catch (RejectedExecutionException error) {
            showError(entries.get(index), "后台写入任务已停止，请重新下载。");
        }
    }

    private void render() {
        if (exiting || resourcesReleased) return;
        if (items.isEmpty()) {
            titleView.setText("没有可查看的文件");
            counterView.setText("0 / 0");
            prevButton.setEnabled(false);
            nextButton.setEnabled(false);
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
        imageView.setImageDrawable(null);
        imageView.setVisibility(View.GONE);
        videoView.setVisibility(View.GONE);
        if (item.isVideo) {
            videoView.setVisibility(View.VISIBLE);
        } else {
            loadImage(index);
        }
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
            MaterialCheckBox check = (MaterialCheckBox) tagGroup.getChildAt(i * 2);
            check.setEnabled(item.tagsEditable);
            check.setChecked(item.selectedTags.contains(name));
        }
        updatingTagChecks = false;
        tagPanel.setContentDescription(item.tagsEditable
                ? "当前文件标签列表" : "当前文件已提交，标签只读");
    }

    private void ensureTagRows() {
        boolean reusable = tagGroup.getChildCount() == tagNames.size() * 2;
        if (reusable) {
            for (int i = 0; i < tagNames.size(); i++) {
                View row = tagGroup.getChildAt(i * 2);
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
            check.setTextSize(14);
            check.setGravity(Gravity.CENTER_VERTICAL);
            check.setMinHeight(dp(48));
            check.setPadding(dp(2), 0, dp(6), 0);
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
            tagGroup.addView(check, new LinearLayout.LayoutParams(-1, dp(48)));
            View divider = new View(this);
            divider.setBackgroundColor(0x44ffffff);
            tagGroup.addView(divider, new LinearLayout.LayoutParams(-1, 1));
        }
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
        imageView.setImageBitmap(image);
        imageView.setVisibility(View.VISIBLE);
        imageView.resetView();
    }

    private void showError(Models.RemoteEntry entry, String message) {
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

    private void openVideo() {
        Models.RemoteEntry entry = entries.get(index);
        java.net.URL url = OpenListClient.resolveUrl(client.getBaseUrl(), entry.rawUrl);
        if (url == null) {
            progressHost.setVisibility(View.VISIBLE);
            client.getFileInfo(entry.path, (ok, message, fetched) -> {
                progressHost.setVisibility(View.GONE);
                if (ok && !fetched.rawUrl.isEmpty()) {
                    entry.rawUrl = fetched.rawUrl;
                    items.get(index).rawUrl = fetched.rawUrl;
                    java.net.URL resolved = OpenListClient.resolveUrl(client.getBaseUrl(), fetched.rawUrl);
                    if (resolved != null) startExternal(resolved.toString());
                } else if (titleView != null) {
                    titleView.setText(ok ? "OpenList 没有返回视频地址" : message);
                }
            });
            return;
        }
        startExternal(url.toString());
    }

    private void startExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException error) {
            showError(entries.get(index), "系统没有可用的视频播放器。");
        }
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
        if (client != null) {
            client.shutdown();
            client = null;
        }
        // The window is being destroyed now, so drop the last drawable references before
        // releasing the cache. This prevents a full-size decoded image from surviving the
        // transition back to MainActivity.
        if (imageView != null) imageView.setImageDrawable(null);
        if (videoView != null) videoView.setImageDrawable(null);
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
        if (videoView != null) videoView.setImageDrawable(null);
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
        releaseImageResources();
        super.onDestroy();
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

    private void hideStatusBar() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .hide(WindowInsetsCompat.Type.statusBars());
    }

    private void applyTopBarInsets(View view) {
        final int baseHeight = dp(56);
        final int topBreathingRoom = dp(12);
        ViewCompat.setOnApplyWindowInsetsListener(view, (target, windowInsets) -> {
            Insets safe = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout());
            target.setPadding(safe.left, safe.top + topBreathingRoom, safe.right, 0);
            ViewGroup.LayoutParams params = target.getLayoutParams();
            if (params != null) {
                params.height = baseHeight + safe.top + topBreathingRoom;
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
