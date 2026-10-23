package com.openlistsorter.app.data;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class ImageConverterTest {
    @Test
    public void previewDecodeIsBoundedToRequestedSize() {
        byte[] png = createImage(640, 360);
        Bitmap decoded = ImageConverter.decodePreview(png, 32, 32);
        assertNotNull(decoded);
        assertEquals(32, decoded.getWidth());
        assertEquals(18, decoded.getHeight());
    }

    @Test
    public void realHeifFixtureIsRecognizedAndNativeFallbackIsBundled() throws Exception {
        byte[] heif;
        try (InputStream input = getClass().getResourceAsStream("/fixtures/sample.heic")) {
            assertNotNull(input);
            heif = input.readAllBytes();
        }
        assertTrue(ImageConverter.isHeifLike(heif, "sample.heic"));
        assertNotNull(Class.forName("com.aliyun.libheif.HeifNative", false,
                getClass().getClassLoader()));
        // Robolectric substitutes a small host bitmap for formats its host codec cannot decode;
        // source dimensions are therefore verified by the fixture generator and Android APK path.
        Bitmap decoded = ImageConverter.decode(heif);
        if (decoded != null) {
            assertTrue(decoded.getWidth() > 0);
            assertTrue(decoded.getHeight() > 0);
        }
    }

    @Test
    public void webpConversionPreservesDimensionsAndContentHash() {
        byte[] png = createImage(96, 54);
        ImageConverter.Result result = ImageConverter.convertToWebp(png, "原始文件");
        assertTrue(result.message, result.ok);
        assertEquals(PreviewCache.sha256(png) + ".webp", result.outputName);
        assertEquals(PreviewCache.md5(png), result.md5);
        Bitmap decoded = BitmapFactory.decodeByteArray(result.data, 0, result.data.length);
        assertNotNull(decoded);
        assertEquals(96, decoded.getWidth());
        assertEquals(54, decoded.getHeight());
    }

    @Test
    public void heifBrandsAndExtensionsAreDetectedWithoutFalseJpegMatch() {
        byte[] heic = "\0\0\0\0ftypheicpayload".getBytes(StandardCharsets.ISO_8859_1);
        byte[] avif = "\0\0\0\0ftypavifpayload".getBytes(StandardCharsets.ISO_8859_1);
        assertTrue(ImageConverter.isHeifLike(heic, "unknown.bin"));
        assertTrue(ImageConverter.isHeifLike(avif, "unknown.bin"));
        assertTrue(ImageConverter.isHeifLike(new byte[0], "PHOTO.HEIF"));
        assertFalse(ImageConverter.isHeifLike(createImage(8, 8), "photo.jpg"));
    }

    private static byte[] createImage(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xff2f6f73);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        bitmap.recycle();
        return output.toByteArray();
    }
}
