package com.openlistsorter.app.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.openlistsorter.app.data.Models;
import com.openlistsorter.app.network.OpenListClient;

import java.util.ArrayList;

/** Full-height remote directory picker with a clickable breadcrumb path. */
public final class RemoteDirectoryDialog {
    public interface SelectionCallback {
        void onSelected(String path);
    }

    private final Context context;
    private final OpenListClient client;
    private final SelectionCallback callback;
    private final AlertDialog dialog;
    private final LinearLayout breadcrumb;
    private final TextView statusView;
    private final ListView listView;
    private final ArrayAdapter<String> adapter;
    private final ArrayList<Models.RemoteEntry> directories = new ArrayList<>();
    private int requestGeneration;
    private String currentPath;

    public RemoteDirectoryDialog(Context context, OpenListClient client, String startPath,
                                 SelectionCallback callback) {
        this.context = context;
        this.client = client;
        this.callback = callback;
        currentPath = Models.normalizeRemotePath(startPath);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(4));

        HorizontalScrollView pathScroll = new HorizontalScrollView(context);
        pathScroll.setHorizontalScrollBarEnabled(false);
        breadcrumb = new LinearLayout(context);
        breadcrumb.setGravity(Gravity.CENTER_VERTICAL);
        pathScroll.addView(breadcrumb, new HorizontalScrollView.LayoutParams(-2, dp(48)));
        root.addView(pathScroll, new LinearLayout.LayoutParams(-1, dp(48)));

        listView = new ListView(context);
        listView.setDivider(new ColorDrawable(0x22000000));
        listView.setDividerHeight(1);
        adapter = new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1,
                new ArrayList<>()) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setPadding(dp(12), dp(8), dp(12), dp(8));
                view.setTextSize(15);
                return view;
            }
        };
        listView.setAdapter(adapter);
        root.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1));

        statusView = new TextView(context);
        statusView.setTextColor(0xff5f6b68);
        statusView.setPadding(dp(8), dp(8), dp(8), dp(4));
        root.addView(statusView, new LinearLayout.LayoutParams(-1, -2));

        dialog = new MaterialAlertDialogBuilder(context)
                .setTitle("选择远程目录")
                .setView(root)
                .setNegativeButton("取消", null)
                .setPositiveButton("选择当前目录", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                callback.onSelected(currentPath);
                dialog.dismiss();
            });
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(0xfff8faf8));
                window.setLayout(-1, -1);
            }
        });
        dialog.setOnDismissListener(ignored -> ++requestGeneration);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < directories.size()) {
                load(directories.get(position).path, false);
            }
        });
    }

    public void show() {
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(-1, -1);
            window.setBackgroundDrawable(new ColorDrawable(0xfff8faf8));
        }
        load(currentPath, false);
    }

    private void load(String path, boolean refresh) {
        currentPath = Models.normalizeRemotePath(path);
        final int generation = ++requestGeneration;
        renderBreadcrumb();
        statusView.setText("正在加载目录...");
        adapter.clear();
        adapter.add("正在加载目录...");
        setBusy(true);
        client.listDirectory(currentPath, refresh, (ok, message, loadedPath, entries) -> {
            if (generation != requestGeneration || !dialog.isShowing()
                    || !loadedPath.equals(currentPath)) return;
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
                    adapter.add("目录  " + entry.name);
                }
            }
            if (directories.isEmpty()) adapter.add("当前目录没有子目录");
            statusView.setText("子目录 " + directories.size() + " 项 · 点击目录进入");
        });
    }

    private void renderBreadcrumb() {
        breadcrumb.removeAllViews();
        ArrayList<String> paths = new ArrayList<>();
        paths.add("/");
        if (!"/".equals(currentPath)) {
            String[] parts = currentPath.substring(1).split("/");
            String value = "";
            for (String part : parts) {
                value += "/" + part;
                paths.add(value);
            }
        }
        for (int i = 0; i < paths.size(); i++) {
            final String path = paths.get(i);
            TextView crumb = new TextView(context);
            crumb.setText(i == 0 ? "根目录" : path.substring(path.lastIndexOf('/') + 1));
            crumb.setTextSize(14);
            crumb.setTextColor(i == paths.size() - 1 ? 0xff17201e : 0xff35685f);
            crumb.setGravity(Gravity.CENTER_VERTICAL);
            crumb.setPadding(dp(8), 0, dp(8), 0);
            crumb.setMinHeight(dp(48));
            crumb.setClickable(true);
            crumb.setFocusable(true);
            crumb.setContentDescription("打开目录 " + crumb.getText());
            crumb.setTypeface(Typeface.DEFAULT, i == paths.size() - 1 ? Typeface.BOLD : Typeface.NORMAL);
            crumb.setOnClickListener(view -> load(path, false));
            breadcrumb.addView(crumb, new LinearLayout.LayoutParams(-2, dp(48)));
            if (i < paths.size() - 1) {
                TextView separator = new TextView(context);
                separator.setText("/");
                separator.setGravity(Gravity.CENTER);
                separator.setTextColor(0xff6b7673);
                breadcrumb.addView(separator, new LinearLayout.LayoutParams(dp(20), dp(48)));
            }
        }
    }

    private void setBusy(boolean busy) {
        listView.setEnabled(!busy);
        if (dialog.isShowing()) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
