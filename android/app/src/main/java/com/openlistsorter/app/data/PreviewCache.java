package com.openlistsorter.app.data;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static com.openlistsorter.app.data.Models.RemoteEntry;

public final class PreviewCache {
    private final File directory;

    public PreviewCache(Context context) {
        directory = new File(context.getCacheDir(), "previews");
        if (!directory.exists()) {
            //noinspection ResultOfMethodCallIgnored
            directory.mkdirs();
        }
    }

    public byte[] loadRawData(RemoteEntry entry) {
        return read(fileFor(entry, "raw", ".bin"));
    }

    public boolean storeRawData(RemoteEntry entry, byte[] data) {
        return write(fileFor(entry, "raw", ".bin"), data);
    }

    public Bitmap loadImage(RemoteEntry entry) {
        byte[] data = read(fileFor(entry, "preview", ".webp"));
        return data == null ? null : BitmapFactory.decodeByteArray(data, 0, data.length);
    }

    public boolean storeImage(RemoteEntry entry, Bitmap image) {
        if (image == null) {
            return false;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!image.compress(Bitmap.CompressFormat.WEBP, 100, output)) {
            return false;
        }
        return write(fileFor(entry, "preview", ".webp"), output.toByteArray());
    }

    public File rawFile(RemoteEntry entry) {
        return fileFor(entry, "raw", ".bin");
    }

    public void clear() {
        deleteRecursively(directory);
        //noinspection ResultOfMethodCallIgnored
        directory.mkdirs();
    }

    public String directoryPath() {
        return directory.getAbsolutePath();
    }

    private File fileFor(RemoteEntry entry, String variant, String extension) {
        String material = "preview-android-v1\n" + variant + "\n" + entry.path + "\n"
                + entry.size + "\n" + entry.modified + "\n" + entry.name;
        return new File(directory, sha256(material.getBytes(StandardCharsets.UTF_8)) + extension);
    }

    private static byte[] read(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    output.write(buffer, 0, count);
                }
            }
            byte[] result = output.toByteArray();
            return result.length == 0 ? null : result;
        } catch (IOException error) {
            return null;
        }
    }

    private static boolean write(File file, byte[] data) {
        if (file == null || data == null || data.length == 0) {
            return false;
        }
        File parent = file.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            return false;
        }
        File temp = new File(parent, file.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(data);
            output.flush();
            if (file.exists() && !file.delete()) {
                return false;
            }
            return temp.renameTo(file);
        } catch (IOException error) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return false;
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
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
            for (byte value : digest) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
