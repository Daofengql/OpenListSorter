package com.openlistsorter.app.data;

import android.graphics.Bitmap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class PreviewCacheTest {
    private File directory;

    @Before
    public void setUp() throws Exception {
        directory = Files.createTempDirectory("preview-cache-test").toFile();
    }

    @After
    public void tearDown() {
        delete(directory);
    }

    @Test
    public void cacheKeyInvalidatesWhenRemoteMetadataChanges() {
        PreviewCache cache = new PreviewCache(directory, 1024, 1024 * 1024);
        Models.RemoteEntry first = entry("/photos/a.heic", 100, "v1");
        Models.RemoteEntry changed = entry("/photos/a.heic", 101, "v2");
        byte[] payload = new byte[]{1, 2, 3, 4};
        assertTrue(cache.storeRawData(first, payload));
        assertArrayEquals(payload, cache.loadRawData(first));
        assertFalse(cache.hasRawData(changed));
        assertEquals(1, cache.stats().fileCount);
    }

    @Test
    public void diskCacheEvictsLeastRecentlyUsedFileAtCapacity() {
        PreviewCache cache = new PreviewCache(directory, 64, 1024 * 1024);
        Models.RemoteEntry first = entry("/a.jpg", 40, "1");
        Models.RemoteEntry second = entry("/b.jpg", 40, "1");
        assertTrue(cache.storeRawData(first, bytes(40, (byte) 1)));
        assertTrue(cache.rawFile(first).setLastModified(1L));
        assertTrue(cache.storeRawData(second, bytes(40, (byte) 2)));
        assertFalse(cache.hasRawData(first));
        assertTrue(cache.hasRawData(second));
        assertTrue(cache.stats().totalBytes <= 64L);
    }

    @Test
    public void newestFileSurvivesWhenCacheIsFull() {
        PreviewCache cache = new PreviewCache(directory, 64, 1024 * 1024);
        Models.RemoteEntry first = entry("/old.jpg", 40, "1");
        Models.RemoteEntry second = entry("/new.jpg", 40, "1");
        assertTrue(cache.storeRawData(first, bytes(40, (byte) 1)));
        assertTrue(cache.storeRawData(second, bytes(40, (byte) 2)));
        assertNull(cache.loadRawData(first));
        assertArrayEquals(bytes(40, (byte) 2), cache.loadRawData(second));
    }

    @Test
    public void concurrentWritesAreAtomicAndLeaveNoTemporaryFiles() throws Exception {
        PreviewCache cache = new PreviewCache(directory, 1024 * 1024, 1024 * 1024);
        Models.RemoteEntry entry = entry("/same.heic", 4096, "1");
        ArrayList<byte[]> candidates = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(8);
        for (int index = 0; index < 8; index++) {
            byte[] value = bytes(4096, (byte) index);
            candidates.add(value);
            new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    cache.storeRawData(entry, value);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "cache-writer").start();
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS));
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        byte[] stored = cache.loadRawData(entry);
        assertNotNull(stored);
        assertTrue(candidates.stream().anyMatch(value -> Arrays.equals(value, stored)));
        File[] temporary = directory.listFiles(file -> file.getName().contains(".tmp-"));
        assertNotNull(temporary);
        assertEquals(0, temporary.length);
    }

    @Test
    public void concurrentMemoryReadsWritesAndStatsRemainConsistent() throws Exception {
        PreviewCache cache = new PreviewCache(directory, 1024 * 1024, 4 * 1024 * 1024);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(8);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int worker = 0; worker < 8; worker++) {
            final int workerId = worker;
            new Thread(() -> {
                try {
                    start.await();
                    for (int index = 0; index < 100; index++) {
                        Models.RemoteEntry item = entry("/memory/" + (index % 12) + ".png",
                                4096, String.valueOf(workerId));
                        cache.putImageInMemory(item,
                                Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888));
                        cache.hasImage(item);
                        cache.stats();
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    done.countDown();
                }
            }, "memory-cache-worker").start();
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
        PreviewCache.Stats stats = cache.stats();
        assertTrue(stats.memoryEntryCount >= 1);
        assertTrue(stats.memoryBytes <= 4 * 1024 * 1024);
    }

    @Test
    public void memoryImageKeepsOriginalDimensions() {
        PreviewCache cache = new PreviewCache(directory, 1024 * 1024, 1024 * 1024);
        Models.RemoteEntry entry = entry("/full.png", 100, "1");
        Bitmap bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
        cache.putImageInMemory(entry, bitmap);
        Bitmap loaded = cache.loadImage(entry);
        assertNotNull(loaded);
        assertEquals(320, loaded.getWidth());
        assertEquals(180, loaded.getHeight());
    }

    @Test
    public void oversizedBitmapIsNotCachedOrAllowedToEvictSmallerEntry() {
        PreviewCache cache = new PreviewCache(directory, 1024 * 1024, 2048);
        Models.RemoteEntry smallEntry = entry("/small.png", 100, "1");
        Models.RemoteEntry largeEntry = entry("/large.png", 100, "1");
        Bitmap small = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        Bitmap large = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);

        assertTrue(cache.putImageInMemory(smallEntry, small));
        assertFalse(cache.putImageInMemory(largeEntry, large));
        assertSame(small, cache.loadImage(smallEntry));
        assertNull(cache.loadImage(largeEntry));
        assertEquals(1, cache.stats().memoryEntryCount);
        assertFalse(large.isRecycled());
    }

    @Test
    public void detachedMemoryCacheCanRecycleBitmapStorage() {
        PreviewCache cache = new PreviewCache(directory, 1024 * 1024, 1024 * 1024);
        Models.RemoteEntry entry = entry("/detached.png", 100, "1");
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        assertTrue(cache.putImageInMemory(entry, bitmap));

        cache.clearMemoryAndRecycle();

        assertTrue(bitmap.isRecycled());
        assertEquals(0, cache.stats().memoryEntryCount);
        assertEquals(0, cache.stats().memoryBytes);
    }

    @Test
    public void diskDecodeCanSkipMemoryCacheDuringScreenTransition() {
        PreviewCache cache = new PreviewCache(directory, 1024 * 1024, 1024 * 1024);
        Models.RemoteEntry entry = entry("/transition.png", 100, "1");
        Bitmap source = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output));
        source.recycle();
        assertTrue(cache.storeDecodedJpeg(entry, output.toByteArray()));

        Bitmap decoded = cache.loadImage(entry, 32, 32, false);

        assertNotNull(decoded);
        assertEquals(0, cache.stats().memoryEntryCount);
        decoded.recycle();
    }

    private static Models.RemoteEntry entry(String path, long size, String modified) {
        Models.RemoteEntry entry = new Models.RemoteEntry();
        entry.path = path;
        entry.name = path.substring(path.lastIndexOf('/') + 1);
        entry.size = size;
        entry.modified = modified;
        return entry;
    }

    private static byte[] bytes(int count, byte value) {
        byte[] result = new byte[count];
        Arrays.fill(result, value);
        return result;
    }

    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
