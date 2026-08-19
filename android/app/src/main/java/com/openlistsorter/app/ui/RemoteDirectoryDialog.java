package com.openlistsorter.app.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

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
        Button rootButton = button("根目录");
        Button upButton = button("上一级");
        Button refreshButton = button("刷新");
        actions.addView(rootButton);
        actions.addView(upButton);
        actions.addView(refreshButton);
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

        dialog = new AlertDialog.Builder(context)
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

        rootButton.setOnClickListener(view -> load("/", false));
        upButton.setOnClickListener(view -> load(Models.parentRemotePath(currentPath), false));
        refreshButton.setOnClickListener(view -> load(currentPath, true));
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < directories.size()) {
                load(directories.get(position).path, false);
            }
        });
    }

    public void show() {
        dialog.show();
        load(currentPath, false);
    }

    private void load(String path, boolean refresh) {
        currentPath = Models.normalizeRemotePath(path);
        pathView.setText(currentPath);
        statusView.setText("正在加载目录...");
        adapter.clear();
        adapter.add("正在加载目录...");
        client.listDirectory(currentPath, refresh, (ok, message, loadedPath, entries) -> {
            if (!loadedPath.equals(currentPath)) {
                return;
            }
            adapter.clear();
            directories.clear();
            if (!ok) {
                adapter.add("目录加载失败");
                statusView.setText(message);
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

    private Button button(String text) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
