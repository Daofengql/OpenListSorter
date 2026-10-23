package com.openlistsorter.app.data;

import android.os.Parcel;
import android.os.Parcelable;

import org.json.JSONObject;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class Models {
    private static volatile Set<String> customImageSuffixes = Collections.emptySet();
    private static volatile Set<String> customVideoSuffixes = Collections.emptySet();
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
        if (customVideoSuffixes.contains(suffix)) return false;
        return customImageSuffixes.contains(suffix)
                || suffix.equals("jpg") || suffix.equals("jpeg") || suffix.equals("png")
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
        if (customImageSuffixes.contains(suffix)) return false;
        return customVideoSuffixes.contains(suffix)
                || suffix.equals("mp4") || suffix.equals("mkv") || suffix.equals("mov")
                || suffix.equals("avi") || suffix.equals("webm") || suffix.equals("flv")
                || suffix.equals("wmv") || suffix.equals("m4v") || suffix.equals("ts")
                || suffix.equals("m2ts") || suffix.equals("mts") || suffix.equals("ogv")
                || suffix.equals("ogg") || suffix.equals("3gp") || suffix.equals("3g2")
                || suffix.equals("mpeg") || suffix.equals("mpg") || suffix.equals("f4v");
    }

    public static void configureCustomSuffixes(Collection<String> imageSuffixes,
                                                Collection<String> videoSuffixes) {
        customImageSuffixes = normalizedSuffixes(imageSuffixes);
        customVideoSuffixes = normalizedSuffixes(videoSuffixes);
    }

    public static Set<String> normalizedSuffixes(Collection<String> values) {
        HashSet<String> result = new HashSet<>();
        if (values == null) return result;
        for (String value : values) {
            if (value == null) continue;
            String suffix = value.trim().toLowerCase(Locale.US);
            while (suffix.startsWith(".")) suffix = suffix.substring(1);
            if (!suffix.isEmpty() && suffix.matches("[a-z0-9]+")) result.add(suffix);
        }
        return result;
    }

    public static String videoMimeType(String name) {
        String suffix = lowerSuffix(name);
        if (suffix.equals("webm") || suffix.equals("ogv") || suffix.equals("ogg")) {
            return suffix.equals("webm") ? "video/webm" : "video/ogg";
        }
        if (suffix.equals("mkv")) return "video/x-matroska";
        if (suffix.equals("mov")) return "video/quicktime";
        if (suffix.equals("ts") || suffix.equals("mts") || suffix.equals("m2ts")) {
            return "video/mp2t";
        }
        if (suffix.equals("3gp") || suffix.equals("3g2")) return "video/3gpp";
        if (suffix.equals("mp4") || suffix.equals("m4v") || suffix.equals("f4v")) {
            return "video/mp4";
        }
        if (suffix.equals("avi")) return "video/avi";
        return "";
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

    /** 轻量可序列化视图项，用于在全屏预览页之间传递远程文件信息。 */
    public static final class PreviewItem implements Parcelable {
        public final String name;
        public final String path;
        public final long size;
        public final String modified;
        public String rawUrl;
        public final boolean isVideo;
        public final ArrayList<String> selectedTags;
        public boolean tagsEditable = true;
        public boolean removed;

        public PreviewItem(RemoteEntry entry) {
            this.name = entry.name;
            this.path = entry.path;
            this.size = entry.size;
            this.modified = entry.modified;
            this.rawUrl = entry.rawUrl;
            this.isVideo = isVideoFileName(entry.name);
            this.selectedTags = new ArrayList<>();
        }

        protected PreviewItem(Parcel in) {
            this(in, true);
        }

        private PreviewItem(Parcel in, boolean readRemoved) {
            name = in.readString();
            path = in.readString();
            size = in.readLong();
            modified = in.readString();
            rawUrl = in.readString();
            isVideo = in.readByte() != 0;
            ArrayList<String> tags = in.createStringArrayList();
            selectedTags = tags == null ? new ArrayList<>() : tags;
            tagsEditable = in.readByte() != 0;
            removed = readRemoved && in.readByte() != 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(name);
            dest.writeString(path);
            dest.writeLong(size);
            dest.writeString(modified);
            dest.writeString(rawUrl);
            dest.writeByte((byte) (isVideo ? 1 : 0));
            dest.writeStringList(selectedTags);
            dest.writeByte((byte) (tagsEditable ? 1 : 0));
            dest.writeByte((byte) (removed ? 1 : 0));
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<PreviewItem> CREATOR = new Creator<PreviewItem>() {
            @Override
            public PreviewItem createFromParcel(Parcel source) {
                return new PreviewItem(source);
            }

            @Override
            public PreviewItem[] newArray(int size) {
                return new PreviewItem[size];
            }
        };

        public static final Creator<PreviewItem> LEGACY_CREATOR = new Creator<PreviewItem>() {
            @Override
            public PreviewItem createFromParcel(Parcel source) {
                return new PreviewItem(source, false);
            }

            @Override
            public PreviewItem[] newArray(int size) {
                return new PreviewItem[size];
            }
        };

        public RemoteEntry toEntry() {
            RemoteEntry entry = new RemoteEntry();
            entry.name = name;
            entry.path = path;
            entry.size = size;
            entry.modified = modified;
            entry.rawUrl = rawUrl;
            return entry;
        }
    }
}
