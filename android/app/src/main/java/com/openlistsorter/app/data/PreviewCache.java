package com.openlistsorter.app.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;

import static com.openlistsorter.app.data.Models.RemoteEntry;

/** A bounded disk cache with a small decoded-image memory tier. */
public final class PreviewCache {
    public static final long DEFAULT_MAX_DISK_BYTES = 256L * 1024L * 1024L;
    public static final int MIN_DISK_MB = 64;
    public static final int MAX_DISK_MB = 4096;
    private static final String CACHE_PREFS = "cache";
    private static final String MAX_DISK_MB_KEY = "maxDiskMb";
    // Keep the decoded tier small. A single 2048px ARGB bitmap is already about 16 MiB;
    // retaining several of them while an Activity is changing can otherwise trigger process
    // eviction on phones with a modest graphics heap.
    public static final int DEFAULT_MAX_MEMORY_BYTES = 8 * 1024 * 1024;

    public static final class Stats {
        public final int fileCount;
        public final long totalBytes;
        public final int memoryEntryCount;
        public final int memoryBytes;

        Stats(int fileCount, long totalBytes, int memoryEntryCount, int memoryBytes) {
            this.fileCount = fileCount;
            this.totalBytes = totalBytes;
            this.memoryEntryCount = memoryEntryCount;
            this.memoryBytes = memoryBytes;
        }
    }

    private final File directory;
    private final long maxDiskBytes;
    private final Object diskLock = new Object();
    private final Object memoryLock = new Object();
    private long diskGeneration;
    private final int maxMemoryBytes;
    private final LruCache<String, Bitmap> imageMemoryCache;

    public PreviewCache(Context context) {
        this(new File(context.getCacheDir(), "previews"), configuredMaxDiskBytes(context),
                DEFAULT_MAX_MEMORY_BYTES);
    }

    public static int configuredMaxDiskMb(Context context) {
        if (context == null) return (int) (DEFAULT_MAX_DISK_BYTES / (1024L * 1024L));
        SharedPreferences prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
        return clampDiskMb(prefs.getInt(MAX_DISK_MB_KEY,
                (int) (DEFAULT_MAX_DISK_BYTES / (1024L * 1024L))));
    }

    public static void configureMaxDiskMb(Context context, int megabytes) {
        if (context == null) return;
        context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).edit()
                .putInt(MAX_DISK_MB_KEY, clampDiskMb(megabytes)).apply();
    }

    private static long configuredMaxDiskBytes(Context context) {
        return configuredMaxDiskMb(context) * 1024L * 1024L;
    }

    private static int clampDiskMb(int megabytes) {
        return Math.max(MIN_DISK_MB, Math.min(MAX_DISK_MB, megabytes));
    }

    PreviewCache(File directory, long maxDiskBytes, int maxMemoryBytes) {
        this.directory = directory;
        this.maxDiskBytes = Math.max(1L, maxDiskBytes);
        this.maxMemoryBytes = Math.max(1, maxMemoryBytes);
        this.imageMemoryCache = new LruCache<>(this.maxMemoryBytes) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value == null ? 0 : value.getAllocationByteCount();
            }
        };
        synchronized (diskLock) {
            ensureDirectory();
            removeTemporaryFiles();
            trimToSizeLocked();
        }
    }

    public byte[] loadRawData(RemoteEntry entry) {
        return read(fileFor(entry, "raw", ".bin"));
    }

    public boolean storeRawData(RemoteEntry entry, byte[] data) {
        return write(fileFor(entry, "raw", ".bin"), data);
    }

    public byte[] loadDecodedJpeg(RemoteEntry entry) {
        return read(fileFor(entry, "decoded-jpeg", ".jpg"));
    }

    public boolean storeDecodedJpeg(RemoteEntry entry, byte[] data) {
        return write(fileFor(entry, "decoded-jpeg", ".jpg"), data);
    }

    public boolean hasRawData(RemoteEntry entry) {
        return isCachedFile(fileFor(entry, "raw", ".bin"));
    }

    public boolean hasImage(RemoteEntry entry) {
        return memoryGet(cacheKey(entry, "preview-bitmap")) != null
                || isCachedFile(fileFor(entry, "decoded-jpeg", ".jpg"));
    }

    public boolean putImageInMemory(RemoteEntry entry, Bitmap image) {
        return image != null && !image.isRecycled()
                && memoryPut(cacheKey(entry, "preview-bitmap"), image);
    }

    public Bitmap loadImage(RemoteEntry entry) {
        return loadImage(entry, 2048, 2048);
    }

    public Bitmap loadImage(RemoteEntry entry, int maxWidth, int maxHeight) {
        return loadImage(entry, maxWidth, maxHeight, true);
    }

    public Bitmap loadImage(RemoteEntry entry, int maxWidth, int maxHeight,
                            boolean cacheDecodedImage) {
        String memoryKey = cacheKey(entry, "preview-bitmap");
        Bitmap memory = memoryGet(memoryKey);
        if (memory != null && !memory.isRecycled()) {
            return memory;
        }
        byte[] data = loadDecodedJpeg(entry);
        Bitmap decoded = data == null ? null : ImageConverter.decodePreview(data,
                Math.max(1, maxWidth), Math.max(1, maxHeight));
        if (decoded != null && cacheDecodedImage) {
            memoryPut(memoryKey, decoded);
        }
        return decoded;
    }

    /** Stores the full-quality JPEG used as the deterministic HEIF/AVIF conversion source. */
    public boolean storeImage(RemoteEntry entry, Bitmap image) {
        byte[] encoded = encodeJpeg(image, 100);
        if (encoded == null || !storeDecodedJpeg(entry, encoded)) {
            return false;
        }
        memoryPut(cacheKey(entry, "preview-bitmap"), image);
        return true;
    }

    public File rawFile(RemoteEntry entry) {
        return fileFor(entry, "raw", ".bin");
    }

    public Stats stats() {
        int memoryEntries;
        int memoryBytes;
        synchronized (memoryLock) {
            memoryEntries = imageMemoryCache.snapshot().size();
            memoryBytes = imageMemoryCache.size();
        }
        synchronized (diskLock) {
            File[] files = directory.listFiles(File::isFile);
            int count = 0;
            long bytes = 0L;
            if (files != null) {
                for (File file : files) {
                    if (file.getName().contains(".tmp-")) continue;
                    count++;
                    bytes += Math.max(0L, file.length());
                }
            }
            return new Stats(count, bytes, memoryEntries, memoryBytes);
        }
    }

    public void clear() {
        clearMemory();
        synchronized (diskLock) {
            diskGeneration++;
            deleteRecursively(directory);
            ensureDirectory();
        }
    }

    /** Drops decoded bitmap references without touching the reusable on-disk cache. */
    public void clearMemory() {
        synchronized (memoryLock) {
            imageMemoryCache.evictAll();
        }
    }

    /**
     * Releases decoded bitmap storage after every ImageView using this cache has detached its
     * drawable. Callers must not use this while a cached bitmap can still be rendered.
     */
    public void clearMemoryAndRecycle() {
        ArrayList<Bitmap> bitmaps;
        synchronized (memoryLock) {
            bitmaps = new ArrayList<>(imageMemoryCache.snapshot().values());
            imageMemoryCache.evictAll();
        }
        for (Bitmap bitmap : bitmaps) {
            if (bitmap != null && !bitmap.isRecycled()) {
                try {
                    bitmap.recycle();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    public String directoryPath() {
        return directory.getAbsolutePath();
    }

    public long maxDiskBytes() {
        return maxDiskBytes;
    }

    private Bitmap memoryGet(String key) {
        synchronized (memoryLock) {
            return imageMemoryCache.get(key);
        }
    }

    private boolean memoryPut(String key, Bitmap image) {
        if (image == null || image.isRecycled()
                || image.getAllocationByteCount() > maxMemoryBytes) {
            return false;
        }
        synchronized (memoryLock) {
            imageMemoryCache.put(key, image);
            return imageMemoryCache.get(key) == image;
        }
    }

    private File fileFor(RemoteEntry entry, String variant, String extension) {
        return new File(directory, cacheKey(entry, variant) + extension);
    }

    private static String cacheKey(RemoteEntry entry, String variant) {
        String material = "preview-android-v2\n" + variant + "\n" + entry.path + "\n"
                + entry.size + "\n" + entry.modified + "\n" + entry.name;
        return sha256(material.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] read(File file) {
        synchronized (diskLock) {
            if (file == null || !file.isFile()) {
                return null;
            }
            try (FileInputStream input = new FileInputStream(file);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count > 0) output.write(buffer, 0, count);
                }
                byte[] result = output.toByteArray();
                if (result.length == 0) return null;
                //noinspection ResultOfMethodCallIgnored
                file.setLastModified(System.currentTimeMillis());
                return result;
            } catch (IOException error) {
                return null;
            }
        }
    }

    private boolean isCachedFile(File file) {
        synchronized (diskLock) {
            return file != null && file.isFile() && file.length() > 0L;
        }
    }

    private boolean write(File file, byte[] data) {
        if (file == null || data == null || data.length == 0 || data.length > maxDiskBytes) {
            return false;
        }
        final long generation;
        synchronized (diskLock) {
            if (!ensureDirectory()) return false;
            generation = diskGeneration;
        }
        File temp = new File(directory, file.getName() + ".tmp-"
                + Thread.currentThread().getId() + "-" + System.nanoTime());
        try {
            // Close the temporary file before replacing the cache entry. Windows keeps an open
            // FileOutputStream exclusively locked, so renaming it inside this block fails.
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(data);
                output.flush();
            }
            synchronized (diskLock) {
                if (generation != diskGeneration || !ensureDirectory()) return false;
                if (file.exists() && !file.delete()) return false;
                if (!temp.renameTo(file)) return false;
                //noinspection ResultOfMethodCallIgnored
                file.setLastModified(System.currentTimeMillis());
                trimToSizeLocked(file);
                return file.isFile();
            }
        } catch (IOException error) {
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private boolean ensureDirectory() {
        return directory.isDirectory() || directory.mkdirs();
    }

    private void removeTemporaryFiles() {
        File[] files = directory.listFiles(file -> file.isFile() && file.getName().contains(".tmp-"));
        if (files == null) return;
        for (File file : files) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private void trimToSizeLocked() {
        trimToSizeLocked(null);
    }

    private void trimToSizeLocked(File protectedFile) {
        File[] listed = directory.listFiles(file -> file.isFile() && !file.getName().contains(".tmp-"));
        if (listed == null) return;
        ArrayList<File> files = new ArrayList<>();
        long total = 0L;
        for (File file : listed) {
            files.add(file);
            total += Math.max(0L, file.length());
        }
        if (total <= maxDiskBytes) return;
        files.sort((left, right) -> {
            if (protectedFile != null && left.equals(protectedFile)) return 1;
            if (protectedFile != null && right.equals(protectedFile)) return -1;
            return Comparator.comparingLong(File::lastModified).compare(left, right);
        });
        for (File file : files) {
            long length = Math.max(0L, file.length());
            if (file.delete()) total -= length;
            if (total <= maxDiskBytes) break;
        }
    }

    private static byte[] encodeJpeg(Bitmap image, int quality) {
        if (image == null || image.isRecycled()) return null;
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            return image.compress(Bitmap.CompressFormat.JPEG, quality, output) && output.size() > 0
                    ? output.toByteArray() : null;
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    public static String sha256(byte[] data) {
        return digest(data, "SHA-256");
    }

    public static String md5(byte[] data) {
        return digest(data, "MD5");
    }

    private static String digest(byte[] data, String algorithm) {
        try {
            byte[] digest = MessageDigest.getInstance(algorithm).digest(data);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
