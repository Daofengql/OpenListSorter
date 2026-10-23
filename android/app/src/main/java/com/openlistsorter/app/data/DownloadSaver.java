package com.openlistsorter.app.data;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Saves downloaded files without opening a document picker. */
public final class DownloadSaver {
    private DownloadSaver() {
    }

    public static String save(Context context, String fileName, byte[] data) throws IOException {
        if (context == null || data == null) throw new IOException("保存数据为空");
        String safeName = safeFileName(fileName);
        String mime = mimeType(safeName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveToMediaStore(context, safeName, mime, data);
        }
        return saveToAppExternalDirectory(context, safeName, data);
    }

    private static String saveToMediaStore(Context context, String fileName,
                                           String mime, byte[] data) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Uri collection;
        String relativePath;
        String location;
        if (mime.startsWith("image/")) {
            collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            relativePath = Environment.DIRECTORY_PICTURES + "/OpenList Sorter";
            location = "图片/OpenList Sorter";
        } else if (mime.startsWith("video/")) {
            collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            relativePath = Environment.DIRECTORY_MOVIES + "/OpenList Sorter";
            location = "视频/OpenList Sorter";
        } else {
            collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            relativePath = Environment.DIRECTORY_DOWNLOADS + "/OpenList Sorter";
            location = "下载/OpenList Sorter";
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri target = resolver.insert(collection, values);
        if (target == null) throw new IOException("无法创建媒体库文件");
        try {
            try (OutputStream output = resolver.openOutputStream(target, "w")) {
                if (output == null) throw new IOException("无法打开媒体库文件");
                output.write(data);
                output.flush();
            }
            ContentValues published = new ContentValues();
            published.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(target, published, null, null);
            return location;
        } catch (IOException | RuntimeException error) {
            resolver.delete(target, null, null);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("写入媒体库失败", error);
        }
    }

    private static String saveToAppExternalDirectory(Context context, String fileName,
                                                     byte[] data) throws IOException {
        File base;
        String mime = mimeType(fileName);
        if (mime.startsWith("image/")) base = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        else if (mime.startsWith("video/")) base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        else base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) base = context.getFilesDir();
        File directory = new File(base, "OpenList Sorter");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建应用保存目录");
        }
        File target = uniqueFile(directory, fileName);
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(data);
            output.flush();
        }
        return "应用专用目录/OpenList Sorter";
    }

    private static File uniqueFile(File directory, String name) {
        File candidate = new File(directory, name);
        if (!candidate.exists()) return candidate;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String suffix = dot > 0 ? name.substring(dot) : "";
        int number = 1;
        do {
            candidate = new File(directory, stem + " (" + number++ + ")" + suffix);
        } while (candidate.exists());
        return candidate;
    }

    private static String safeFileName(String name) {
        String value = name == null ? "download" : name.trim();
        if (value.isEmpty()) value = "download";
        value = value.replaceAll("[\\\\/:*?\"<>|]", "_");
        return value.length() > 180 ? value.substring(0, 180) : value;
    }

    private static String mimeType(String name) {
        String suffix = Models.lowerSuffix(name);
        String guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(suffix);
        if (guessed != null && !guessed.isEmpty()) return guessed;
        if (Models.isImageFileName(name)) return "image/*";
        if (Models.isVideoFileName(name)) {
            String video = Models.videoMimeType(name);
            return video.isEmpty() ? "video/*" : video;
        }
        return "application/octet-stream";
    }
}
