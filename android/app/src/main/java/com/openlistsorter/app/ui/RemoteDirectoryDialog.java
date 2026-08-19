package com.openlistsorter.app.ui;

import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.openlistsorter.app.data.Models;
import com.openlistsorter.app.network.OpenListClient;

import java.util.ArrayList;

public final class RemoteDirectoryDialog {
    public interface SelectionCallback {
        void onSelected(String path);
    }

    private final Context context;
    private final OpenListClient client;
    private final SelectionCallback callback;
    private final AlertDialog dialog;
    private final TextView pathView;
    private final TextView statusView;
    private final ListView listView;
    private final ArrayAdapter<String> adapter;
    private final ArrayList<Models.RemoteEntry> directories = new ArrayList<>();
    private final Button rootButton;
    private final Button upButton;
    private final Button refreshButton;
    private int requestGeneration;
    private int lastClickPosition = -1;
    private long lastClickTime;
    private String currentPath;

    public RemoteDirectoryDialog(Context context, OpenListClient client, String startPath,
                                 SelectionCallback callback) {
        this.context = context;
        this.client = client;
        this.callback = callback;
        currentPath = Models.normalizeRemotePath(startPath);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), dp(8));

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        rootButton = button("根目录");
        upButton = button("上一级");
        refreshButton = button("刷新");
        actions.addView(rootButton, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(upButton, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(refreshButton, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(actions);

        pathView = new TextView(context);
        pathView.setTypeface(Typeface.MONOSPACE);
        pathView.setPadding(0, dp(12), 0, dp(8));
        root.addView(pathView);

        listView = new ListView(context);
        adapter = new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, new ArrayList<>());
        listView.setAdapter(adapter);
        root.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        statusView = new TextView(context);
        statusView.setTextColor(0xff667085);
        statusView.setPadding(0, dp(8), 0, 0);
        root.addView(statusView);

        dialog = new MaterialAlertDialogBuilder(context)
                .setTitle("选择远程目录")
                .setView(root)
                .setNegativeButton("取消", null)
                .setPositiveButton("选择当前目录", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    callback.onSelected(currentPath);
                    dialog.dismiss();
                }));
        dialog.setOnDismissListener(ignored -> ++requestGeneration);

        rootButton.setOnClickListener(view -> load("/", false));
        upButton.setOnClickListener(view -> load(Models.parentRemotePath(currentPath), false));
        refreshButton.setOnClickListener(view -> load(currentPath, true));
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= directories.size()) {
                return;
            }
            long now = android.os.SystemClock.uptimeMillis();
            if (position == lastClickPosition && now - lastClickTime < 450L) {
                lastClickPosition = -1;
                load(directories.get(position).path, false);
                return;
            }
            lastClickPosition = position;
            lastClickTime = now;
            statusView.setText("已选中: " + directories.get(position).name + "，再次点击进入");
        });
    }

    public void show() {
        dialog.show();
        load(currentPath, false);
    }

    private void load(String path, boolean refresh) {
        currentPath = Models.normalizeRemotePath(path);
        final int generation = ++requestGeneration;
        pathView.setText(currentPath);
        statusView.setText("正在加载目录...");
        adapter.clear();
        adapter.add("正在加载目录...");
        setBusy(true);
        client.listDirectory(currentPath, refresh, (ok, message, loadedPath, entries) -> {
            if (generation != requestGeneration || !dialog.isShowing()
                    || !loadedPath.equals(currentPath)) {
                return;
            }
            setBusy(false);
            adapter.clear();
            directories.clear();
            if (!ok) {
                adapter.add("目录加载失败");
                statusView.setText("目录加载失败: " + message);
                return;
            }
            for (Models.RemoteEntry entry : entries) {
                if (entry.isDir) {
                    directories.add(entry);
                    adapter.add(entry.name);
                }
            }
            if (directories.isEmpty()) {
                adapter.add("当前目录没有子目录");
            }
            statusView.setText("子目录: " + directories.size());
        });
    }

    private void setBusy(boolean busy) {
        rootButton.setEnabled(!busy);
        upButton.setEnabled(!busy);
        refreshButton.setEnabled(!busy);
        listView.setEnabled(!busy);
        if (dialog.isShowing()) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy);
        }
    }

    private Button button(String text) {
        MaterialButton button = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinHeight(dp(44));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
