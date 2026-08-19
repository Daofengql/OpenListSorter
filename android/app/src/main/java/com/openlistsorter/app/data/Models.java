package com.openlistsorter.app.data;

import org.json.JSONObject;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Locale;

public final class Models {
    private Models() {
    }

    public static final class RemoteEntry {
        public String name = "";
        public String path = "";
        public long size;
        public boolean isDir;
        public int type;
        public String modified = "";
        public String created = "";
        public String thumb = "";
        public String rawUrl = "";

        public RemoteEntry copy() {
            RemoteEntry result = new RemoteEntry();
            result.name = name;
            result.path = path;
            result.size = size;
            result.isDir = isDir;
            result.type = type;
            result.modified = modified;
            result.created = created;
            result.thumb = thumb;
            result.rawUrl = rawUrl;
            return result;
        }

        public static RemoteEntry fromJson(JSONObject object, String parentPath) {
            RemoteEntry entry = new RemoteEntry();
            entry.name = object.optString("name", "");
            entry.path = joinRemotePath(parentPath, entry.name);
            entry.size = object.optLong("size", 0L);
            entry.isDir = object.optBoolean("is_dir", false);
            entry.type = object.optInt("type", 0);
            entry.modified = object.optString("modified", "");
            entry.created = object.optString("created", "");
            entry.thumb = object.optString("thumb", "");
            entry.rawUrl = object.optString("raw_url", "");
            return entry;
        }
    }

    public static final class TagMapping {
        public String name = "";
        public String targetPath = "/";

        public TagMapping() {
        }

        public TagMapping(String name, String targetPath) {
            this.name = name;
            this.targetPath = targetPath;
        }
    }

    public static final class FileItem {
        public RemoteEntry entry;
        public final ArrayList<String> selectedTags = new ArrayList<>();
        public boolean submitted;
        public boolean failed;
        public String lastError = "";

        public FileItem(RemoteEntry entry) {
            this.entry = entry;
        }
    }

    public static String normalizeRemotePath(String value) {
        String path = value == null ? "" : value.trim().replace('\\', '/');
        if (path.isEmpty()) {
            return "/";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (path.contains("//")) {
            path = path.replace("//", "/");
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    public static String joinRemotePath(String directory, String name) {
        String parent = normalizeRemotePath(directory);
        if ("/".equals(parent)) {
            return "/" + name;
        }
        return parent + "/" + name;
    }

    public static String parentRemotePath(String path) {
        String normalized = normalizeRemotePath(path);
        if ("/".equals(normalized)) {
            return "/";
        }
        int slash = normalized.lastIndexOf('/');
        return slash <= 0 ? "/" : normalized.substring(0, slash);
    }

    public static String lowerSuffix(String name) {
        if (name == null) {
            return "";
        }
        int slash = name.lastIndexOf('/');
        int dot = name.lastIndexOf('.');
        if (dot <= slash || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    public static boolean isImageFileName(String name) {
        String suffix = lowerSuffix(name);
        return suffix.equals("jpg") || suffix.equals("jpeg") || suffix.equals("png")
                || suffix.equals("gif") || suffix.equals("bmp") || suffix.equals("webp")
                || suffix.equals("tif") || suffix.equals("tiff") || suffix.equals("heic")
                || suffix.equals("heif") || suffix.equals("avif") || suffix.equals("ico")
                || suffix.equals("svg") || suffix.equals("jxl") || suffix.equals("raw")
                || suffix.equals("cr2") || suffix.equals("cr3") || suffix.equals("nef")
                || suffix.equals("arw") || suffix.equals("dng") || suffix.equals("orf")
                || suffix.equals("rw2") || suffix.equals("raf") || suffix.equals("pef");
    }

    public static boolean isVideoFileName(String name) {
        String suffix = lowerSuffix(name);
        return suffix.equals("mp4") || suffix.equals("mkv") || suffix.equals("mov")
                || suffix.equals("avi") || suffix.equals("webm") || suffix.equals("flv")
                || suffix.equals("wmv") || suffix.equals("m4v") || suffix.equals("ts");
    }

    public static String formatBytes(long bytes) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        if (unit == 0) {
            return bytes + " B";
        }
        DecimalFormat format = new DecimalFormat(value >= 10 ? "0.0" : "0.00");
        return format.format(value) + " " + units[unit];
    }

    public static String statusText(FileItem item) {
        if (item.submitted) {
            return "已提交";
        }
        if (item.failed) {
            return "失败";
        }
        if (!item.selectedTags.isEmpty()) {
            return "待保存";
        }
        return "未处理";
    }

    public static String fileType(RemoteEntry entry) {
        if (entry.isDir) {
            return "目录";
        }
        String suffix = lowerSuffix(entry.name);
        return suffix.isEmpty() ? "未知文件" : suffix.toUpperCase(Locale.US) + " 文件";
    }
}
