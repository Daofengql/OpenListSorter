package com.openlistsorter.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.openlistsorter.app.data.ConnectionProfile;
import com.openlistsorter.app.data.ConnectionStore;
import com.openlistsorter.app.data.ImageConverter;
import com.openlistsorter.app.data.Models;
import com.openlistsorter.app.data.PreviewCache;
import com.openlistsorter.app.network.OpenListClient;
import com.openlistsorter.app.ui.RemoteDirectoryDialog;
import com.openlistsorter.app.ui.ZoomImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

public final class MainActivity extends Activity {
    private static final int PAGE_SIZE = 20;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
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

    private String sourcePath = "/";
    private final ArrayList<Models.FileItem> files = new ArrayList<>();
    private final ArrayList<Models.TagMapping> tags = new ArrayList<>();
    private int currentIndex = -1;
    private int currentPage;
    private boolean classificationMode;
    private boolean busy;
    private boolean overwrite;
    private boolean convertOnSave;
    private int preloadGeneration;

    private TextView sourcePathView;
    private TextView statsView;
    private TextView pageInfo;
    private LinearLayout fileList;
    private TextView previewTitle;
    private TextView previewInfo;
    private ZoomImageView imageView;
    private ImageView videoPlaceholder;
    private LinearLayout tagList;
    private Button enterButton;
    private Button saveButton;
    private CheckBox overwriteCheck;
    private CheckBox convertCheck;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        client = new OpenListClient();
        previewCache = new PreviewCache(this);
        profile = ConnectionStore.load(this);
        showConnectionScreen();
        if (profile.autoLogin && profile.hasCredentials()) {
            mainHandler.postDelayed(this::startConnection, 180);
        }
    }

    @Override
    protected void onDestroy() {
        client.shutdown();
        super.onDestroy();
    }

    private void showConnectionScreen() {
        root = baseRoot();
        root.setPadding(dp(16), dp(20), dp(16), dp(16));
        LinearLayout card = panel();
        TextView title = heading("连接到 OpenList", 24);
        card.addView(title);
        TextView subtitle = text("使用 endpoint + token key，或使用账号密码登录。", 0xff667085);
        card.addView(subtitle);

        endpointEdit = input("https://openlist.example.com");
        card.addView(labelled("Endpoint", endpointEdit));

        authGroup = new RadioGroup(this);
        authGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton tokenMode = radio("Token key");
        RadioButton passwordMode = radio("账号密码");
        tokenMode.setId(View.generateViewId());
        passwordMode.setId(View.generateViewId());
        authGroup.addView(tokenMode);
        authGroup.addView(passwordMode);
        tokenMode.setChecked(true);
        card.addView(authGroup);

        tokenEdit = input("OpenList token");
        tokenEdit.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(labelled("Token key", tokenEdit));
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

        rememberCheck = new CheckBox(this);
        rememberCheck.setText("记住我");
        autoLoginCheck = new CheckBox(this);
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

        status = text(" ", 0xff667085);
        card.addView(status);
        connectButton = primaryButton("连接");
        Button cancel = button("退出");
        LinearLayout actions = row();
        actions.setGravity(Gravity.END);
        actions.addView(cancel);
        actions.addView(connectButton);
        card.addView(actions);
        connectButton.setOnClickListener(view -> startConnection());
        cancel.setOnClickListener(view -> finish());

        endpointEdit.setText(profile.endpoint);
        tokenEdit.setText(profile.token);
        usernameEdit.setText(profile.username);
        passwordEdit.setText(profile.password);
        if (profile.authMode == ConnectionProfile.AuthMode.PASSWORD) {
            passwordMode.setChecked(true);
        }
        setContentView(root);
    }

    private void startConnection() {
        ConnectionProfile requested = readProfile();
        if (requested.endpoint.isEmpty()) {
            showError("请输入 endpoint");
            return;
        }
        if (!requested.hasCredentials()) {
            showError("请输入 token key，或完整的用户名和密码");
            return;
        }
        busy = true;
        connectButton.setEnabled(false);
        status.setText("正在连接...");
        OpenListClient.SimpleCallback callback = (ok, message) -> {
            busy = false;
            connectButton.setEnabled(true);
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
            showMainScreen();
        };
        if (requested.authMode == ConnectionProfile.AuthMode.PASSWORD) {
            client.login(requested.endpoint, requested.username, requested.password, callback);
        } else {
            client.validateToken(requested.endpoint, requested.token, callback);
        }
    }

    private ConnectionProfile readProfile() {
        ConnectionProfile result = new ConnectionProfile();
        result.endpoint = endpointEdit.getText().toString().trim();
        boolean password = authGroup.getCheckedRadioButtonId() != -1
                && ((RadioButton) authGroup.findViewById(authGroup.getCheckedRadioButtonId())).getText()
                .toString().contains("账号");
        result.authMode = password ? ConnectionProfile.AuthMode.PASSWORD : ConnectionProfile.AuthMode.TOKEN;
        result.token = tokenEdit.getText().toString().trim();
        result.username = usernameEdit.getText().toString().trim();
        result.password = passwordEdit.getText().toString();
        result.rememberMe = rememberCheck.isChecked();
        result.autoLogin = autoLoginCheck.isChecked() && result.rememberMe;
        return result;
    }

    private void showMainScreen() {
        root = baseRoot();
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(10), dp(14), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout titleRow = row();
        TextView title = heading("OpenList Sorter", 22);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button logout = button("注销");
        titleRow.addView(logout);
        content.addView(titleRow);
        logout.setOnClickListener(view -> logout());

        status = text("已连接: " + client.getBaseUrl(), 0xff667085);
        content.addView(status);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setVisibility(View.GONE);
        content.addView(progress, new LinearLayout.LayoutParams(-1, dp(6)));

        LinearLayout task = panel();
        task.addView(heading("分类任务", 17));
        sourcePathView = text(sourcePath, Color.DKGRAY);
        sourcePathView.setTypeface(Typeface.MONOSPACE);
        Button browse = primaryButton("选择源目录");
        enterButton = primaryButton("进入分类模式");
        LinearLayout sourceRow = new LinearLayout(this);
        sourceRow.setOrientation(LinearLayout.VERTICAL);
        sourceRow.addView(browse, new LinearLayout.LayoutParams(-1, -2));
        sourceRow.addView(sourcePathView, new LinearLayout.LayoutParams(-1, -2));
        sourceRow.addView(enterButton, new LinearLayout.LayoutParams(-1, -2));
        task.addView(sourceRow);
        LinearLayout options = row();
        overwriteCheck = new CheckBox(this);
        overwriteCheck.setText("覆盖同名文件");
        convertCheck = new CheckBox(this);
        convertCheck.setText("保存时转 WebP");
        options.addView(overwriteCheck);
        options.addView(convertCheck);
        task.addView(options);
        Button convertCurrent = button("仅转换当前");
        Button deleteCurrent = button("删除当前");
        deleteCurrent.setTextColor(Color.WHITE);
        deleteCurrent.setBackgroundResource(R.drawable.danger_button_background);
        Button downloadCurrent = button("下载当前");
        LinearLayout fileActions = row();
        fileActions.addView(downloadCurrent, new LinearLayout.LayoutParams(0, -2, 1));
        fileActions.addView(convertCurrent, new LinearLayout.LayoutParams(0, -2, 1));
        fileActions.addView(deleteCurrent, new LinearLayout.LayoutParams(0, -2, 1));
        task.addView(fileActions);
        content.addView(task);

        statsView = text("总文件 0 | 已提交 0 | 待保存 0 | 未处理 0", 0xff172033);
        statsView.setPadding(0, dp(10), 0, dp(10));
        content.addView(statsView);

        LinearLayout filesPanel = panel();
        filesPanel.addView(heading("当前目录文件", 17));
        LinearLayout pageRow = row();
        Button previous = button("上一页");
        Button next = button("下一页");
        pageInfo = text("第 0 / 0 页", 0xff667085);
        pageInfo.setGravity(Gravity.CENTER);
        pageRow.addView(previous);
        pageRow.addView(pageInfo, new LinearLayout.LayoutParams(0, -2, 1));
        pageRow.addView(next);
        filesPanel.addView(pageRow);
        fileList = new LinearLayout(this);
        fileList.setOrientation(LinearLayout.VERTICAL);
        filesPanel.addView(fileList);
        content.addView(filesPanel);

        LinearLayout previewPanel = panel();
        previewTitle = heading("预览", 17);
        previewPanel.addView(previewTitle);
        previewInfo = text("请选择文件", 0xff667085);
        previewInfo.setPadding(dp(10), dp(8), dp(10), dp(8));
        previewInfo.setBackgroundResource(R.drawable.preview_background);
        previewPanel.addView(previewInfo);
        imageView = new ZoomImageView(this);
        imageView.setBackgroundResource(R.drawable.preview_background);
        imageView.setVisibility(View.GONE);
        previewPanel.addView(imageView, new LinearLayout.LayoutParams(-1, dp(320)));
        videoPlaceholder = new ImageView(this);
        videoPlaceholder.setImageResource(android.R.drawable.ic_media_play);
        videoPlaceholder.setVisibility(View.GONE);
        previewPanel.addView(videoPlaceholder, new LinearLayout.LayoutParams(-1, dp(120)));
        content.addView(previewPanel);

        LinearLayout tagsPanel = panel();
        tagsPanel.addView(heading("分类 tag", 17));
        Button addTag = button("新增 tag");
        Button manageTags = button("管理 tag");
        Button clearTags = button("清空当前");
        LinearLayout tagActions = row();
        tagActions.addView(addTag, new LinearLayout.LayoutParams(0, -2, 1));
        tagActions.addView(manageTags, new LinearLayout.LayoutParams(0, -2, 1));
        tagActions.addView(clearTags, new LinearLayout.LayoutParams(0, -2, 1));
        tagsPanel.addView(tagActions);
        tagList = new LinearLayout(this);
        tagList.setOrientation(LinearLayout.VERTICAL);
        tagsPanel.addView(tagList);
        saveButton = primaryButton("保存待提交");
        tagsPanel.addView(saveButton);
        content.addView(tagsPanel);

        browse.setOnClickListener(view -> pickDirectory(sourcePath, selected -> {
            sourcePath = selected;
            sourcePathView.setText(sourcePath);
            saveLocalSettings();
        }));
        enterButton.setOnClickListener(view -> {
            if (classificationMode) exitClassificationMode(); else enterClassificationMode();
        });
        previous.setOnClickListener(view -> setPage(currentPage - 1));
        next.setOnClickListener(view -> setPage(currentPage + 1));
        addTag.setOnClickListener(view -> addTag());
        manageTags.setOnClickListener(view -> manageTags());
        clearTags.setOnClickListener(view -> clearCurrentTags());
        saveButton.setOnClickListener(view -> savePending());
        downloadCurrent.setOnClickListener(view -> downloadCurrent());
        convertCurrent.setOnClickListener(view -> convertCurrent());
        deleteCurrent.setOnClickListener(view -> deleteCurrent());
        overwriteCheck.setOnCheckedChangeListener((button, checked) -> overwrite = checked);
        convertCheck.setOnCheckedChangeListener((button, checked) -> convertOnSave = checked);
        loadLocalSettings();
        refreshTags();
        updateStats();
    }

    private void loadLocalSettings() {
        android.content.SharedPreferences prefs = getSharedPreferences("task", MODE_PRIVATE);
        sourcePath = Models.normalizeRemotePath(prefs.getString("sourcePath", sourcePath));
        sourcePathView.setText(sourcePath);
        overwrite = prefs.getBoolean("overwrite", false);
        convertOnSave = prefs.getBoolean("convertOnSave", false);
        overwriteCheck.setChecked(overwrite);
        convertCheck.setChecked(convertOnSave);
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
                .putBoolean("convertOnSave", convertOnSave).putInt("tagCount", tags.size());
        for (int i = 0; i < tags.size(); i++) {
            editor.putString("tagName" + i, tags.get(i).name).putString("tagPath" + i, tags.get(i).targetPath);
        }
        editor.apply();
    }

    private void pickDirectory(String startPath, RemoteDirectoryDialog.SelectionCallback callback) {
        new RemoteDirectoryDialog(this, client, startPath, callback).show();
    }

    private void enterClassificationMode() {
        if (!client.hasConnection()) {
            showError("请先连接 OpenList");
            return;
        }
        classificationMode = true;
        enterButton.setText("退出分类模式");
        previewCache.clear();
        files.clear();
        currentIndex = -1;
        updateStats();
        setBusy(true, "正在读取源目录: " + sourcePath);
        client.listDirectory(sourcePath, false, (ok, message, path, entries) -> {
            setBusy(false, "");
            if (!ok) {
                classificationMode = false;
                enterButton.setText("进入分类模式");
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
        int pending = pendingCount();
        if (pending > 0) {
            new AlertDialog.Builder(this).setTitle("退出分类模式")
                    .setMessage("当前有 " + pending + " 个已选择但未保存的文件，退出会放弃选择。继续吗？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("退出", (dialog, which) -> finishClassificationMode()).show();
        } else {
            finishClassificationMode();
        }
    }

    private void finishClassificationMode() {
        classificationMode = false;
        enterButton.setText("进入分类模式");
        files.clear();
        currentIndex = -1;
        previewCache.clear();
        renderFileList();
        updateStats();
        showInfo("已退出分类模式");
    }

    private void setCurrentIndex(int index) {
        if (index < 0 || index >= files.size()) return;
        currentIndex = index;
        int targetPage = index / PAGE_SIZE;
        if (targetPage != currentPage) currentPage = targetPage;
        renderFileList();
        refreshTags();
        updateStats();
        renderCurrentFile();
    }

    private void setPage(int page) {
        int count = pageCount();
        if (count == 0) return;
        currentPage = Math.max(0, Math.min(page, count - 1));
        int start = currentPage * PAGE_SIZE;
        if (currentIndex < start || currentIndex >= pageEnd()) currentIndex = start;
        renderFileList();
        renderCurrentFile();
        preloadCurrentPage();
    }

    private void preloadCurrentPage() {
        final int generation = ++preloadGeneration;
        final int start = currentPage * PAGE_SIZE;
        final int end = pageEnd();
        preloadNext(generation, start, end);
    }

    private void preloadNext(int generation, int index, int end) {
        if (generation != preloadGeneration || index >= end || !classificationMode) {
            return;
        }
        Models.FileItem item = files.get(index);
        if (!Models.isImageFileName(item.entry.name)) {
            preloadNext(generation, index + 1, end);
            return;
        }
        Bitmap cachedImage = previewCache.loadImage(item.entry);
        if (cachedImage != null) {
            cachedImage.recycle();
            preloadNext(generation, index + 1, end);
            return;
        }
        byte[] cachedRaw = previewCache.loadRawData(item.entry);
        if (cachedRaw != null) {
            if (ImageConverter.isHeifLike(cachedRaw, item.entry.name)) {
                final byte[] decodeData = cachedRaw;
                new Thread(() -> {
                    Bitmap image = ImageConverter.decode(decodeData);
                    if (image != null) {
                        previewCache.storeImage(item.entry, image);
                        image.recycle();
                    }
                    mainHandler.post(() -> preloadNext(generation, index + 1, end));
                }).start();
            } else {
                preloadNext(generation, index + 1, end);
            }
            return;
        }
        if (item.entry.rawUrl.isEmpty()) {
            client.getFileInfo(item.entry.path, (ok, message, entry) -> {
                if (generation != preloadGeneration) return;
                if (ok && !entry.rawUrl.isEmpty()) {
                    item.entry.rawUrl = entry.rawUrl;
                    item.entry.thumb = entry.thumb;
                    preloadDownload(generation, item, index, end);
                } else {
                    preloadNext(generation, index + 1, end);
                }
            });
        } else {
            preloadDownload(generation, item, index, end);
        }
    }

    private void preloadDownload(int generation, Models.FileItem item, int index, int end) {
        java.net.URL url = OpenListClient.resolveUrl(client.getBaseUrl(), item.entry.rawUrl);
        client.downloadUrl(url, (ok, message, data) -> {
            if (generation != preloadGeneration) return;
            if (ok) {
                previewCache.storeRawData(item.entry, data);
                if (ImageConverter.isHeifLike(data, item.entry.name)) {
                    new Thread(() -> {
                        Bitmap image = ImageConverter.decode(data);
                        if (image != null) {
                            previewCache.storeImage(item.entry, image);
                            image.recycle();
                        }
                        mainHandler.post(() -> preloadNext(generation, index + 1, end));
                    }).start();
                    return;
                }
            }
            preloadNext(generation, index + 1, end);
        }, null);
    }

    private int pageCount() {
        return files.isEmpty() ? 0 : (files.size() + PAGE_SIZE - 1) / PAGE_SIZE;
    }

    private int pageEnd() {
        return Math.min(files.size(), currentPage * PAGE_SIZE + PAGE_SIZE);
    }

    private void renderFileList() {
        if (fileList == null) return;
        fileList.removeAllViews();
        int start = currentPage * PAGE_SIZE;
        int end = pageEnd();
        for (int i = start; i < end; i++) {
            final int fileIndex = i;
            Models.FileItem item = files.get(fileIndex);
            Button row = button((fileIndex + 1) + ". " + Models.statusText(item) + "  " + item.entry.name
                    + (item.selectedTags.isEmpty() ? "" : "\n" + String.join(", ", item.selectedTags)));
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setMinHeight(dp(54));
            row.setOnClickListener(view -> setCurrentIndex(fileIndex));
            if (fileIndex == currentIndex) row.setTextColor(0xff365cf5);
            fileList.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        pageInfo.setText(pageCount() == 0 ? "第 0 / 0 页" : "第 " + (currentPage + 1) + " / " + pageCount()
                + "  " + (start + 1) + "-" + end + " / " + files.size());
    }

    private void renderCurrentFile() {
        if (currentIndex < 0 || currentIndex >= files.size()) {
            showInfo("请选择文件");
            return;
        }
        Models.FileItem item = files.get(currentIndex);
        previewTitle.setText(item.entry.name);
        previewInfo.setText(formatEntryInfo(item));
        imageView.setVisibility(View.GONE);
        videoPlaceholder.setVisibility(View.GONE);
        if (Models.isImageFileName(item.entry.name)) {
            loadImage(item);
        } else if (Models.isVideoFileName(item.entry.name)) {
            videoPlaceholder.setVisibility(View.VISIBLE);
            videoPlaceholder.setOnClickListener(view -> openRemote(item));
        }
    }

    private void loadImage(Models.FileItem item) {
        Bitmap cached = previewCache.loadImage(item.entry);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            imageView.setVisibility(View.VISIBLE);
            imageView.resetView();
            return;
        }
        fetchFileData(item, (ok, message, data) -> {
            if (currentIndex < 0 || currentIndex >= files.size()
                    || !files.get(currentIndex).entry.path.equals(item.entry.path)) return;
            if (!ok) {
                showInfo(formatEntryInfo(item) + "\n\n预览失败: " + message);
                return;
            }
            Bitmap image = ImageConverter.decode(data);
            if (image == null) {
                showInfo(formatEntryInfo(item) + "\n\n当前 Android 解码器不支持此图片格式。");
                return;
            }
            previewCache.storeImage(item.entry, image);
            imageView.setImageBitmap(image);
            imageView.setVisibility(View.VISIBLE);
            imageView.resetView();
        }, "正在读取 " + item.entry.name);
    }

    private void fetchFileData(Models.FileItem item, OpenListClient.DownloadCallback callback, String label) {
        byte[] cached = previewCache.loadRawData(item.entry);
        if (cached != null) {
            callback.onResult(true, "cached", cached);
            return;
        }
        if (!item.entry.rawUrl.isEmpty()) {
            downloadEntry(item.entry, OpenListClient.resolveUrl(client.getBaseUrl(), item.entry.rawUrl), callback, label);
            return;
        }
        client.getFileInfo(item.entry.path, (ok, message, entry) -> {
            if (!ok || entry.rawUrl.isEmpty()) {
                callback.onResult(false, ok ? "OpenList 没有返回下载地址。" : message, new byte[0]);
                return;
            }
            item.entry.rawUrl = entry.rawUrl;
            item.entry.thumb = entry.thumb;
            downloadEntry(item.entry, OpenListClient.resolveUrl(client.getBaseUrl(), entry.rawUrl), callback, label);
        });
    }

    private void downloadEntry(Models.RemoteEntry entry, java.net.URL url,
                               OpenListClient.DownloadCallback callback, String label) {
        setBusy(true, label);
        client.downloadUrl(url, (ok, message, data) -> {
            setBusy(false, "");
            if (ok) previewCache.storeRawData(entry, data);
            callback.onResult(ok, message, data);
        }, (received, total) -> mainHandler.post(() -> {
            if (progress != null) {
                progress.setVisibility(View.VISIBLE);
                progress.setIndeterminate(total <= 0);
                if (total > 0) progress.setProgress((int) Math.min(100, received * 100 / total));
            }
            if (status != null) status.setText(label + ": " + Models.formatBytes(received));
        }));
    }

    private void addTag() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), 0, dp(20), 0);
        EditText name = input("Tag 名称");
        EditText path = input("目标目录，例如 /分类/旅行");
        form.addView(name);
        form.addView(path);
        new AlertDialog.Builder(this).setTitle("新增 tag").setView(form)
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
        new AlertDialog.Builder(this).setTitle("管理 tag").setItems(labels, (dialog, which) ->
                new AlertDialog.Builder(this).setTitle(tags.get(which).name)
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
        form.addView(name);
        form.addView(path);
        new AlertDialog.Builder(this).setTitle("编辑 tag").setView(form)
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
        new AlertDialog.Builder(this).setTitle("删除 tag")
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
        if (tags.isEmpty()) {
            tagList.addView(text("请先新增 tag，并为 tag 填写目标目录。", 0xff667085));
        }
        for (Models.TagMapping tag : tags) {
            CheckBox check = new CheckBox(this);
            check.setText(tag.name + "\n" + tag.targetPath);
            check.setTag(tag.name);
            Models.FileItem item = currentIndex >= 0 && currentIndex < files.size() ? files.get(currentIndex) : null;
            check.setEnabled(classificationMode && item != null && !item.submitted);
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
            tagList.addView(check);
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
        String message = "将顺序处理 " + pending.size() + " 个文件。";
        if (multi) message += "多 tag 文件会复制到多个目标目录。";
        if (converted > 0) message += "图片会转 WebP，全部上传成功后删除源文件。";
        new AlertDialog.Builder(this).setTitle("确认保存").setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> submitNext(pending, 0)).show();
    }

    private void submitNext(ArrayList<Integer> indexes, int position) {
        if (position >= indexes.size()) {
            setBusy(false, "保存完成");
            showInfo("待提交操作已经处理完成");
            return;
        }
        int index = indexes.get(position);
        if (index < 0 || index >= files.size()) {
            submitNext(indexes, position + 1);
            return;
        }
        setBusy(true, "正在提交 " + (position + 1) + " / " + indexes.size() + ": " + files.get(index).entry.name);
        submitFile(index, (ok, message) -> {
            Models.FileItem item = files.get(index);
            item.submitted = ok;
            item.failed = !ok;
            item.lastError = ok ? "" : message;
            renderFileList();
            updateStats();
            if (index == currentIndex) refreshTags();
            submitNext(indexes, position + 1);
        });
    }

    private void submitFile(int index, OpenListClient.SimpleCallback callback) {
        Models.FileItem item = files.get(index);
        ArrayList<String> targets = new ArrayList<>();
        for (String selected : item.selectedTags) {
            Models.TagMapping tag = findTag(selected);
            if (tag == null) {
                callback.onResult(false, "tag 没有绑定目标目录: " + selected);
                return;
            }
            if (!targets.contains(tag.targetPath)) targets.add(tag.targetPath);
        }
        if (convertOnSave && Models.isImageFileName(item.entry.name)) {
            submitConverted(index, targets, callback);
        } else if (targets.size() == 1) {
            client.moveFile(sourcePath, item.entry.name, targets.get(0), overwrite, callback);
        } else {
            copyTargets(index, targets, 0, callback);
        }
    }

    private void copyTargets(int index, ArrayList<String> targets, int position,
                             OpenListClient.SimpleCallback callback) {
        if (position >= targets.size()) {
            callback.onResult(true, "复制完成");
            return;
        }
        client.copyFile(sourcePath, files.get(index).entry.name, targets.get(position), overwrite,
                (ok, message) -> {
                    if (!ok) callback.onResult(false, message);
                    else copyTargets(index, targets, position + 1, callback);
                });
    }

    private void submitConverted(int index, ArrayList<String> targets, OpenListClient.SimpleCallback callback) {
        Models.FileItem item = files.get(index);
        fetchFileData(item, (ok, message, data) -> {
            if (!ok) {
                callback.onResult(false, "获取待转换图片失败: " + message);
                return;
            }
            new Thread(() -> {
                ImageConverter.Result converted = ImageConverter.convertToWebp(data, item.entry.name);
                mainHandler.post(() -> {
                    if (!converted.ok) {
                        callback.onResult(false, converted.message);
                        return;
                    }
                    uploadConverted(index, converted, targets, 0, callback);
                });
            }).start();
        }, "正在读取待转换图片");
    }

    private void uploadConverted(int index, ImageConverter.Result converted, ArrayList<String> targets,
                                 int position, OpenListClient.SimpleCallback callback) {
        if (position >= targets.size()) {
            client.removeFile(Models.parentRemotePath(files.get(index).entry.path), files.get(index).entry.name,
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
                String base = entry.name;
                int dot = base.lastIndexOf('.');
                if (dot > 0) base = base.substring(0, dot);
                if (base.equals(converted.sha256) || base.equals(converted.md5)) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) {
                uploadConverted(index, converted, targets, position + 1, callback);
                return;
            }
            client.uploadFile(uploadPath, converted.data, overwrite, (uploadOk, uploadMessage) -> {
                if (!uploadOk) callback.onResult(false, "上传 WebP 失败: " + uploadMessage);
                else uploadConverted(index, converted, targets, position + 1, callback);
            });
        });
    }

    private void downloadCurrent() {
        if (currentIndex < 0 || currentIndex >= files.size() || busy) return;
        Models.FileItem item = files.get(currentIndex);
        fetchFileData(item, (ok, message, data) -> {
            if (!ok) {
                showError("下载失败: " + message);
                return;
            }
            File downloadDirectory = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
            if (downloadDirectory == null && getCacheDir() != null) {
                downloadDirectory = new File(getCacheDir(), "downloads");
            }
            if (downloadDirectory == null || (!downloadDirectory.exists() && !downloadDirectory.mkdirs())) {
                showError("无法创建下载目录");
                return;
            }
            String safeName = item.entry.name.replaceAll("[\\\\/:*?\\\"<>|]", "_");
            File target = new File(downloadDirectory, safeName);
            try (FileOutputStream output = new FileOutputStream(target)) {
                output.write(data);
                output.flush();
                showInfo("已下载: " + target.getAbsolutePath());
            } catch (IOException error) {
                showError("下载保存失败: " + error.getMessage());
            }
        }, "正在下载 " + item.entry.name);
    }

    private void convertCurrent() {
        if (currentIndex < 0 || currentIndex >= files.size() || busy) return;
        Models.FileItem item = files.get(currentIndex);
        if (!Models.isImageFileName(item.entry.name)) {
            showError("当前文件不是可转换的图片");
            return;
        }
        new AlertDialog.Builder(this).setTitle("仅转换当前图片")
                .setMessage("转 WebP 上传回原目录，成功后删除原文件。继续吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("转换", (dialog, which) -> {
                    ArrayList<String> targets = new ArrayList<>();
                    targets.add(Models.parentRemotePath(item.entry.path));
                    submitConverted(currentIndex, targets, (ok, message) -> {
                        item.submitted = ok;
                        item.failed = !ok;
                        item.lastError = ok ? "" : message;
                        renderFileList();
                        updateStats();
                        if (ok) showInfo(message); else showError(message);
                    });
                }).show();
    }

    private void deleteCurrent() {
        if (currentIndex < 0 || currentIndex >= files.size() || busy) return;
        Models.FileItem item = files.get(currentIndex);
        new AlertDialog.Builder(this).setTitle("确认删除远程文件")
                .setMessage("此操作不可撤销。\n\n" + item.entry.path)
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> client.removeFile(
                        Models.parentRemotePath(item.entry.path), item.entry.name, (ok, message) -> {
                            if (!ok) {
                                item.failed = true;
                                item.lastError = message;
                                showError("删除失败: " + message);
                                return;
                            }
                            files.remove(item);
                            currentIndex = Math.min(currentIndex, files.size() - 1);
                            currentPage = Math.max(0, currentIndex / PAGE_SIZE);
                            renderFileList();
                            updateStats();
                            renderCurrentFile();
                        })).show();
    }

    private void logout() {
        if (busy) {
            showError("当前有任务正在进行，请等待完成");
            return;
        }
        new AlertDialog.Builder(this).setTitle("确认注销")
                .setMessage("将断开连接并关闭自动登录，已保存的连接信息会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("注销", (dialog, which) -> {
                    ConnectionStore.disableAutoLogin(this);
                    client.clearConnection();
                    showConnectionScreen();
                }).show();
    }

    private void openRemote(Models.FileItem item) {
        java.net.URL url = OpenListClient.resolveUrl(client.getBaseUrl(), item.entry.rawUrl);
        if (url != null) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())));
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
        if (saveButton != null) saveButton.setEnabled(classificationMode && pending > 0 && !busy);
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

    private void showInfo(String title, String message) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("确定", null).show();
    }

    private LinearLayout baseRoot() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        value.setBackgroundResource(R.color.page_background);
        return value;
    }

    private LinearLayout panel() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        value.setPadding(dp(14), dp(12), dp(14), dp(12));
        value.setBackgroundResource(R.drawable.panel_background);
        value.setElevation(dp(1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(8), 0, dp(8));
        value.setLayoutParams(params);
        return value;
    }

    private LinearLayout row() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.HORIZONTAL);
        value.setGravity(Gravity.CENTER_VERTICAL);
        return value;
    }

    private TextView heading(String value, int size) {
        TextView result = text(value, 0xff172033);
        result.setTextSize(size);
        result.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
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
        EditText result = new EditText(this);
        result.setHint(hint);
        result.setSingleLine(true);
        result.setTextSize(15);
        result.setTextColor(0xff172033);
        result.setHintTextColor(0xff98A2B3);
        result.setBackgroundResource(R.drawable.input_background);
        result.setPadding(dp(10), 0, dp(10), 0);
        return result;
    }

    private LinearLayout labelled(String label, View child) {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        value.addView(text(label, 0xff667085));
        value.addView(child, new LinearLayout.LayoutParams(-1, -2));
        return value;
    }

    private Button button(String label) {
        Button result = new Button(this);
        result.setText(label);
        result.setAllCaps(false);
        result.setTextSize(14);
        result.setTextColor(0xff172033);
        result.setMinHeight(dp(44));
        result.setMinimumHeight(dp(44));
        result.setPadding(dp(8), 0, dp(8), 0);
        result.setBackgroundResource(R.drawable.secondary_button_background);
        return result;
    }

    private Button primaryButton(String label) {
        Button result = button(label);
        result.setTextColor(Color.WHITE);
        result.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        result.setBackgroundResource(R.drawable.primary_button_background);
        return result;
    }

    private RadioButton radio(String label) {
        RadioButton result = new RadioButton(this);
        result.setText(label);
        result.setAllCaps(false);
        return result;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
