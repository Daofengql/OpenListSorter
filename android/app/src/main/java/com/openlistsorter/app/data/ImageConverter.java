package com.openlistsorter.app.data;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class ImageConverter {
    public static final class Result {
        public boolean ok;
        public byte[] data = new byte[0];
        public String outputName = "";
        public String sha256 = "";
        public String md5 = "";
        public String hashSource = "";
        public String message = "";
    }

    private ImageConverter() {
    }

    public static Bitmap decode(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ImageDecoder.Source source = ImageDecoder.createSource(ByteBuffer.wrap(data));
                return ImageDecoder.decodeBitmap(source, (decoder, info, src) ->
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
            } catch (Exception ignored) {
            }
        }
        return BitmapFactory.decodeByteArray(data, 0, data.length);
    }

    public static byte[] decodeToFullQualityJpeg(byte[] data) {
        Bitmap bitmap = decode(data);
        if (bitmap == null) {
            return null;
        }
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        boolean encoded = bitmap.compress(Bitmap.CompressFormat.JPEG, 100, jpeg);
        bitmap.recycle();
        return encoded && jpeg.size() > 0 ? jpeg.toByteArray() : null;
    }

    public static Result convertToWebp(byte[] data, String hashSource) {
        Result result = new Result();
        if (data == null || data.length == 0) {
            result.message = "图片数据为空，无法转换。";
            return result;
        }
        Bitmap bitmap = decode(data);
        if (bitmap == null) {
            result.message = "Android 图片解码器不支持当前格式，无法转 WebP。";
            return result;
        }

        result.hashSource = hashSource;
        result.sha256 = PreviewCache.sha256(data);
        result.md5 = PreviewCache.md5(data);
        result.outputName = result.sha256 + ".webp";
        ByteArrayOutputStream webp = new ByteArrayOutputStream();
        Bitmap.CompressFormat format = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
        boolean encoded = bitmap.compress(format, 85, webp);
        bitmap.recycle();
        if (!encoded || webp.size() == 0) {
            result.message = "WebP 转换结果为空。";
            return result;
        }
        result.data = webp.toByteArray();
        result.ok = true;
        result.message = "converted";
        return result;
    }

    public static boolean isHeifLike(byte[] data, String sourceName) {
        if (data != null && data.length >= 12
                && data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p') {
            String brands = new String(data, 8, Math.min(data.length - 8, 96), StandardCharsets.ISO_8859_1);
            return brands.contains("avif") || brands.contains("avis") || brands.contains("heic")
                    || brands.contains("heix") || brands.contains("hevc") || brands.contains("hevx")
                    || brands.contains("heis") || brands.contains("hevm") || brands.contains("mif1")
                    || brands.contains("msf1");
        }
        String suffix = Models.lowerSuffix(sourceName);
        return suffix.equals("heic") || suffix.equals("heif") || suffix.equals("avif");
    }
}
