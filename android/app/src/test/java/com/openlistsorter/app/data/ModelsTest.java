package com.openlistsorter.app.data;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ModelsTest {
    @Test
    public void remotePathHelpersNormalizeAndJoin() {
        assertEquals("/", Models.normalizeRemotePath(""));
        assertEquals("/photos/2024", Models.normalizeRemotePath("photos\\2024///"));
        assertEquals("/photos/a.jpg", Models.joinRemotePath("/photos/", "a.jpg"));
        assertEquals("/", Models.parentRemotePath("/a.jpg"));
        assertEquals("/photos", Models.parentRemotePath("/photos/a.jpg"));
    }

    @Test
    public void fileTypeDetectionMatchesQtExtensions() {
        assertTrue(Models.isImageFileName("photo.HEIC"));
        assertTrue(Models.isImageFileName("photo.webp"));
        assertTrue(Models.isVideoFileName("clip.MP4"));
        assertFalse(Models.isVideoFileName("clip.txt"));
        assertEquals("jpeg", Models.lowerSuffix("/photos/a.jpeg"));
    }

    @Test
    public void statusTextFollowsSubmissionState() {
        Models.FileItem item = new Models.FileItem(new Models.RemoteEntry());
        assertEquals("未处理", Models.statusText(item));
        item.selectedTags.add("旅行");
        assertEquals("待保存", Models.statusText(item));
        item.failed = true;
        assertEquals("失败", Models.statusText(item));
        item.submitted = true;
        assertEquals("已提交", Models.statusText(item));
    }
}
