package com.openlistsorter.app.data;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.util.Size;

import androidx.exifinterface.media.ExifInterface;

import com.aliyun.libheif.HeifInfo;
import com.aliyun.libheif.HeifNative;
import com.aliyun.libheif.HeifSize;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class ImageConverter {
    private static final Object DECODE_LOCK = new Object();
    public interface DecodeGuard {
        boolean shouldKeep();
    }
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

    /** Decodes at source resolution for conversion. Call this from a worker thread. */
    public static Bitmap decode(byte[] data) {
        return decodeInternal(data, 0, 0);
    }

    /** Decodes a preview bounded by the requested size when the active decoder supports it. */
    public static Bitmap decodePreview(byte[] data, int maxWidth, int maxHeight) {
        return decodeInternal(data, maxWidth, maxHeight);
    }

    /** Decodes while holding the global native-decoder lock and drops stale results before the
     * next screen is allowed to start another decode. */
    public static Bitmap decodePreview(byte[] data, int maxWidth, int maxHeight,
                                       DecodeGuard guard) {
        synchronized (DECODE_LOCK) {
            Bitmap bitmap = decodeInternalLocked(data, maxWidth, maxHeight);
            if (bitmap != null && guard != null && !guard.shouldKeep()) {
                bitmap.recycle();
                return null;
            }
            return bitmap;
        }
    }

    private static Bitmap decodeInternal(byte[] data, int maxWidth, int maxHeight) {
        synchronized (DECODE_LOCK) {
            return decodeInternalLocked(data, maxWidth, maxHeight);
        }
    }

    private static Bitmap decodeInternalLocked(byte[] data, int maxWidth, int maxHeight) {
        if (data == null || data.length == 0) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ImageDecoder.Source source = ImageDecoder.createSource(ByteBuffer.wrap(data));
                return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    if (maxWidth > 0 && maxHeight > 0) {
                        Size size = info.getSize();
                        float scale = Math.min(1f, Math.min(maxWidth / (float) Math.max(1, size.getWidth()),
                                maxHeight / (float) Math.max(1, size.getHeight())));
                        if (scale < 1f) {
                            decoder.setTargetSize(Math.max(1, Math.round(size.getWidth() * scale)),
                                    Math.max(1, Math.round(size.getHeight() * scale)));
                        }
                    }
                });
            } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
                // BitmapFactory and the media stack provide independent fallback paths.
            }
        }

        Bitmap bitmap = decodeBitmapFactory(data, maxWidth, maxHeight);
        if (bitmap != null) return applyExifOrientation(data, bitmap);
        if (isHeifLike(data, "")) {
            if (maxWidth > 0 && maxHeight > 0) {
                bitmap = decodeHeifWithMediaStack(data, maxWidth, maxHeight);
                if (bitmap != null) return bitmap;
            }
            bitmap = decodeHeifWithLibheif(data, maxWidth, maxHeight);
            if (bitmap != null) return bitmap;
            return decodeHeifWithMediaStack(data, maxWidth, maxHeight);
        }
        return null;
    }

    private static Bitmap decodeBitmapFactory(byte[] data, int maxWidth, int maxHeight) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            if (maxWidth > 0 && maxHeight > 0) {
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, options);
                options.inJustDecodeBounds = false;
                options.inSampleSize = sampleSize(options.outWidth, options.outHeight,
                        maxWidth, maxHeight);
            }
            return scaleInside(BitmapFactory.decodeByteArray(data, 0, data.length, options),
                    maxWidth, maxHeight);
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        }
    }

    private static int sampleSize(int width, int height, int maxWidth, int maxHeight) {
        int sample = 1;
        while (width > 0 && height > 0
                && width / (sample * 2) >= maxWidth
                && height / (sample * 2) >= maxHeight) {
            sample *= 2;
        }
        return sample;
    }


    private static Bitmap applyExifOrientation(byte[] data, Bitmap bitmap) {
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(data));
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            Matrix matrix = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                    matrix.setScale(-1f, 1f);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    matrix.setRotate(180f);
                    break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                    matrix.setScale(1f, -1f);
                    break;
                case ExifInterface.ORIENTATION_TRANSPOSE:
                    matrix.setRotate(90f);
                    matrix.postScale(-1f, 1f);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_90:
                    matrix.setRotate(90f);
                    break;
                case ExifInterface.ORIENTATION_TRANSVERSE:
                    matrix.setRotate(-90f);
                    matrix.postScale(-1f, 1f);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    matrix.setRotate(-90f);
                    break;
                default:
                    return bitmap;
            }
            Bitmap oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(),
                    matrix, true);
            if (oriented != bitmap) bitmap.recycle();
            return oriented;
        } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
            return bitmap;
        }
    }

    private static Bitmap decodeHeifWithLibheif(byte[] data, int maxWidth, int maxHeight) {
        try {
            HeifInfo info = new HeifInfo();
            if (!HeifNative.getInfo(info, data.length, data) || info.getFrameList().isEmpty()) {
                return null;
            }
            HeifSize size = info.getFrameList().get(0);
            int width = size.getWidth();
            int height = size.getHeight();
            if (width <= 0 || height <= 0 || (long) width * height > Integer.MAX_VALUE / 4L) {
                return null;
            }
            long decodedBytes = (long) width * height * 4L;
            boolean boundedPreview = maxWidth > 0 && maxHeight > 0
                    && (width > maxWidth || height > maxHeight);
            // This JNI API can only decode into a full-size Bitmap. Avoid a predictable OOM for
            // tiled panoramas; the platform/media paths above can decode those at target size.
            if (boundedPreview && decodedBytes > 96L * 1024L * 1024L) return null;
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            if (!HeifNative.toRgba(data.length, data, bitmap)) {
                bitmap.recycle();
                return null;
            }
            return scaleInside(bitmap, maxWidth, maxHeight);
        } catch (RuntimeException | LinkageError | OutOfMemoryError ignored) {
            return null;
        }
    }

    private static Bitmap decodeHeifWithMediaStack(byte[] data, int maxWidth, int maxHeight) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try (ByteArrayMediaDataSource source = new ByteArrayMediaDataSource(data)) {
            retriever.setDataSource(source);
            Bitmap bitmap;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && maxWidth > 0 && maxHeight > 0) {
                bitmap = retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        maxWidth, maxHeight);
            } else {
                bitmap = retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            return scaleInside(bitmap, maxWidth, maxHeight);
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }

    private static Bitmap scaleInside(Bitmap bitmap, int maxWidth, int maxHeight) {
        if (bitmap == null || maxWidth <= 0 || maxHeight <= 0) return bitmap;
        float scale = Math.min(1f, Math.min(maxWidth / (float) bitmap.getWidth(),
                maxHeight / (float) bitmap.getHeight()));
        if (scale >= 1f) return bitmap;
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)),
                    Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
            if (scaled != bitmap) bitmap.recycle();
            return scaled;
        } catch (RuntimeException | OutOfMemoryError ignored) {
            // A bounded preview must never fall back to returning the much larger source
            // bitmap. Doing so can keep tens or hundreds of megabytes alive while the user
            // moves between images and is especially dangerous during Activity transitions.
            if (!bitmap.isRecycled()) bitmap.recycle();
            return null;
        }
    }

    public static byte[] decodeToFullQualityJpeg(byte[] data) {
        Bitmap bitmap = decode(data);
        if (bitmap == null) return null;
        try {
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            return bitmap.compress(Bitmap.CompressFormat.JPEG, 100, jpeg) && jpeg.size() > 0
                    ? jpeg.toByteArray() : null;
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        } finally {
            bitmap.recycle();
        }
    }

    public static Result convertToWebp(byte[] data, String hashSource) {
        Result result = new Result();
        result.hashSource = hashSource == null ? "" : hashSource;
        if (data == null || data.length == 0) {
            result.message = "图片数据为空，无法转换。";
            return result;
        }
        Bitmap bitmap = decode(data);
        if (bitmap == null) {
            result.message = "Android 图片解码器与媒体后备解码器均不支持当前格式，无法转 WebP。";
            return result;
        }
        try {
            result.sha256 = PreviewCache.sha256(data);
            result.md5 = PreviewCache.md5(data);
            result.outputName = result.sha256 + ".webp";
            ByteArrayOutputStream webp = new ByteArrayOutputStream();
            Bitmap.CompressFormat format = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
            boolean encoded = bitmap.compress(format, 85, webp);
            if (!encoded || webp.size() == 0) {
                result.message = "WebP 转换结果为空。";
                return result;
            }
            result.data = webp.toByteArray();
            result.ok = true;
            result.message = "converted";
            return result;
        } catch (RuntimeException | OutOfMemoryError error) {
            result.message = "图片过大或编码器失败: " + error.getClass().getSimpleName();
            return result;
        } finally {
            bitmap.recycle();
        }
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

    private static final class ByteArrayMediaDataSource extends MediaDataSource {
        private final byte[] data;

        ByteArrayMediaDataSource(byte[] data) {
            this.data = data;
        }

        @Override
        public int readAt(long position, byte[] buffer, int offset, int size) {
            if (position >= data.length) return -1;
            int count = (int) Math.min(size, data.length - position);
            System.arraycopy(data, (int) position, buffer, offset, count);
            return count;
        }

        @Override
        public long getSize() {
            return data.length;
        }

        @Override
        public void close() {
        }
    }
}
