package com.openlistsorter.app;

import android.app.Dialog;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.button.MaterialButton;
import com.openlistsorter.app.data.ConnectionStore;
import com.openlistsorter.app.data.Models;
import com.openlistsorter.app.ui.PreviewActivity;
import com.openlistsorter.app.ui.PreviewSessionStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35)
public final class UiScreenshotTest {
    @Before
    public void resetPreferences() {
        Context context = RuntimeEnvironment.getApplication();
        ConnectionStore.disableAutoLogin(context);
        context.getSharedPreferences("task", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    public void connectionLandscapeRendersOffline() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            renderAndVerify(activity, "connection-landscape.png", 800, 360);
            assertTouchTargets(activity.findViewById(android.R.id.content), 48);
        }
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    public void fullscreenPreviewKeepsSquareToolbarTargets() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Models.RemoteEntry video = fileItem("演示视频.mp4", "/待分类/演示视频.mp4", 8_000_000L).entry;
        ArrayList<Models.PreviewItem> items = new ArrayList<>();
        items.add(new Models.PreviewItem(video));
        Intent intent = new Intent(context, PreviewActivity.class);
        intent.putParcelableArrayListExtra(PreviewActivity.EXTRA_ITEMS, items);
        intent.putExtra(PreviewActivity.EXTRA_INDEX, 0);
        try (ActivityController<PreviewActivity> controller =
                     Robolectric.buildActivity(PreviewActivity.class, intent)) {
            PreviewActivity activity = controller.setup().get();
            View decor = activity.getWindow().getDecorView();
            renderViewAndVerify(decor, "preview-landscape.png", 800, 360);
            View back = findDescription(decor, "返回");
            View previous = findDescription(decor, "上一张");
            View next = findDescription(decor, "下一张");
            assertNotNull(back);
            assertNotNull(previous);
            assertNotNull(next);
            assertTrue(back.getMeasuredWidth() >= 48 && back.getMeasuredHeight() >= 48);
            assertTrue(previous.getMeasuredWidth() >= 48 && previous.getMeasuredHeight() >= 48);
            assertTrue(next.getMeasuredWidth() >= 48 && next.getMeasuredHeight() >= 48);
            assertTrue(((View) back.getParent()).getMeasuredHeight() >= 56);
        }
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    public void fullscreenTagPanelUsesTheVisibleHostAndCanBeDragged() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ArrayList<Models.PreviewItem> items = new ArrayList<>();
        items.add(new Models.PreviewItem(
                fileItem("测试.jpg", "/待分类/测试.jpg", 1000L).entry));
        Intent intent = new Intent(context, PreviewActivity.class);
        intent.putParcelableArrayListExtra(PreviewActivity.EXTRA_ITEMS, items);
        intent.putStringArrayListExtra(PreviewActivity.EXTRA_TAGS,
                new ArrayList<>(java.util.Collections.singletonList("归档")));
        try (ActivityController<PreviewActivity> controller =
                     Robolectric.buildActivity(PreviewActivity.class, intent)) {
            PreviewActivity activity = controller.setup().get();
            View decor = activity.getWindow().getDecorView();
            decor.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, 800, 360);

            assertNotNull(getField(activity, "imageHost"));
            ViewGroup overlayHost = (ViewGroup) getField(activity, "overlayHost");
            MaterialButton toggle = (MaterialButton) getField(activity, "tagToggle");
            MaterialCardView panel = (MaterialCardView) getField(activity, "tagPanel");
            toggle.performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            decor.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, 800, 360);

            int startLeft = panel.getLeft();
            int startTop = panel.getTop();
            int fixedWidth = panel.getMeasuredWidth();
            int fixedHeight = panel.getMeasuredHeight();
            assertSame(overlayHost, panel.getParent());
            long downTime = SystemClock.uptimeMillis();
            Method drag = PreviewActivity.class.getDeclaredMethod(
                    "handleTagPanelTouch", MotionEvent.class);
            drag.setAccessible(true);
            MotionEvent down = MotionEvent.obtain(downTime, downTime,
                    MotionEvent.ACTION_DOWN, startLeft + 8, startTop + 8, 0);
            MotionEvent move = MotionEvent.obtain(downTime, downTime + 16,
                    MotionEvent.ACTION_MOVE, startLeft - 72, startTop + 36, 0);
            try {
                assertTrue((Boolean) drag.invoke(activity, down));
                assertTrue((Boolean) drag.invoke(activity, move));
                assertTrue(panel.getLeft() != startLeft || panel.getTop() != startTop
                        || (Boolean) getField(activity, "draggingTagPanel"));
                assertEquals(fixedWidth, panel.getMeasuredWidth());
                assertEquals(fixedHeight, panel.getMeasuredHeight());
            } finally {
                down.recycle();
                move.recycle();
            }
        }
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    public void fullscreenPreviewReturnsTagsAndLastViewedPath() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Models.PreviewItem first = new Models.PreviewItem(
                fileItem("第一张.jpg", "/待分类/第一张.jpg", 1000L).entry);
        Models.PreviewItem second = new Models.PreviewItem(
                fileItem("第二张.jpg", "/待分类/第二张.jpg", 1000L).entry);
        ArrayList<Models.PreviewItem> items = new ArrayList<>();
        items.add(first);
        items.add(second);
        Intent intent = new Intent(context, PreviewActivity.class);
        intent.putParcelableArrayListExtra(PreviewActivity.EXTRA_ITEMS, items);
        intent.putStringArrayListExtra(PreviewActivity.EXTRA_TAGS,
                new ArrayList<>(java.util.Collections.singletonList("归档")));
        try (ActivityController<PreviewActivity> controller =
                     Robolectric.buildActivity(PreviewActivity.class, intent)) {
            PreviewActivity activity = controller.setup().get();
            ViewGroup tagGroup = (ViewGroup) getField(activity, "tagGroup");
            View originalTagRow = tagGroup.getChildAt(0);
            TextView tag = findText(activity.getWindow().getDecorView(), "归档");
            assertNotNull(tag);
            tag.performClick();
            Method move = PreviewActivity.class.getDeclaredMethod("move", int.class);
            move.setAccessible(true);
            move.invoke(activity, 1);
            assertSame(originalTagRow, tagGroup.getChildAt(0));
            activity.finish();
            Intent result = Shadows.shadowOf(activity).getResultIntent();
            assertEquals(Activity.RESULT_OK, Shadows.shadowOf(activity).getResultCode());
            assertNotNull(result);
            assertEquals("/待分类/第二张.jpg",
                    result.getStringExtra(PreviewActivity.EXTRA_RESULT_PATH));
            ArrayList<Models.PreviewItem> returned = result.getParcelableArrayListExtra(
                    PreviewActivity.EXTRA_RESULT_ITEMS);
            assertNotNull(returned);
            assertTrue(returned.get(0).selectedTags.contains("归档"));
        }
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    public void largeFullscreenSelectionReturnsOnlySmallSessionId() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ArrayList<Models.PreviewItem> items = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (int tag = 0; tag < 250; tag++) names.add("标签-" + tag);
        for (int file = 0; file < 200; file++) {
            Models.PreviewItem item = new Models.PreviewItem(
                    fileItem("图片" + file + ".jpg", "/大量/图片" + file + ".jpg", 1000L).entry);
            item.selectedTags.addAll(names);
            items.add(item);
        }
        String sessionId = PreviewSessionStore.create(items, names, 0);
        Intent intent = new Intent(context, PreviewActivity.class);
        intent.putExtra(PreviewActivity.EXTRA_SESSION_ID, sessionId);
        try (ActivityController<PreviewActivity> controller =
                     Robolectric.buildActivity(PreviewActivity.class, intent)) {
            PreviewActivity activity = controller.setup().get();
            Method move = PreviewActivity.class.getDeclaredMethod("move", int.class);
            move.setAccessible(true);
            for (int step = 0; step < 30; step++) move.invoke(activity, 1);
            activity.finish();
            Intent result = Shadows.shadowOf(activity).getResultIntent();
            assertNotNull(result);
            assertEquals(sessionId, result.getStringExtra(PreviewActivity.EXTRA_SESSION_ID));
            assertFalse(result.hasExtra(PreviewActivity.EXTRA_RESULT_ITEMS));
            PreviewSessionStore.Session session = PreviewSessionStore.consume(sessionId);
            assertNotNull(session);
            assertEquals("/大量/图片30.jpg", session.currentPath());
            assertEquals(250, session.items.get(0).selectedTags.size());
        }
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-mdpi")
    public void mainScreenRestoresLastFullscreenFile() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            showMainScreen(activity);
            populateClassificationState(activity);
            @SuppressWarnings("unchecked")
            ArrayList<Models.FileItem> files = (ArrayList<Models.FileItem>) getField(activity, "files");
            ArrayList<Models.PreviewItem> returned = new ArrayList<>();
            for (Models.FileItem file : files) {
                Models.PreviewItem item = new Models.PreviewItem(file.entry);
                item.selectedTags.addAll(file.selectedTags);
                returned.add(item);
            }
            returned.get(1).selectedTags.clear();
            returned.get(1).selectedTags.add("精选");
            Method merge = MainActivity.class.getDeclaredMethod(
                    "mergePreviewResult", ArrayList.class, String.class);
            merge.setAccessible(true);
            merge.invoke(activity, returned, returned.get(1).path);
            assertEquals(1, getField(activity, "currentIndex"));
            assertTrue(files.get(1).selectedTags.contains("精选"));
        }
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-mdpi")
    public void mainScreenStartsFullscreenWithOnlySessionId() throws Exception {
        try (ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            showMainScreen(activity);
            populateClassificationState(activity);
            invoke(activity, "openPreview");

            Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull(started);
            assertEquals(PreviewActivity.class.getName(), started.getComponent().getClassName());
            String sessionId = started.getStringExtra(PreviewActivity.EXTRA_SESSION_ID);
            assertNotNull(sessionId);
            assertFalse(started.hasExtra(PreviewActivity.EXTRA_ITEMS));
            assertNotNull(PreviewSessionStore.peek(sessionId));
            assertTrue((Boolean) getField(activity, "previewActive"));
            PreviewSessionStore.discard(sessionId);
        }
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-mdpi")
    public void fullscreenTransitionDoesNotRecycleLateMainPreviewBitmap() throws Exception {
        try (ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            setField(activity, "previewActive", true);
            Bitmap lateResult = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
            Method complete = MainActivity.class.getDeclaredMethod(
                    "finishImageDecode", String.class, Bitmap.class, boolean.class);
            complete.setAccessible(true);
            complete.invoke(activity, "/late-preview", lateResult, false);
            assertFalse("fullscreen transition must not recycle a bitmap still visible to Canvas",
                    lateResult.isRecycled());
            lateResult.recycle();
        }
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-mdpi")
    public void changingPageRefreshesTagSelectionForNewCurrentFile() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            showMainScreen(activity);
            @SuppressWarnings("unchecked")
            ArrayList<Models.FileItem> files = (ArrayList<Models.FileItem>) getField(activity, "files");
            files.clear();
            for (int i = 0; i < 21; i++) {
                Models.FileItem item = fileItem("图片" + i + ".jpg", "/待分类/图片" + i + ".jpg", 1000L);
                if (i == 0) item.selectedTags.add("归档");
                if (i == 20) item.selectedTags.add("精选");
                files.add(item);
            }
            @SuppressWarnings("unchecked")
            ArrayList<Models.TagMapping> tags = (ArrayList<Models.TagMapping>) getField(activity, "tags");
            tags.clear();
            tags.add(new Models.TagMapping("归档", "/归档"));
            tags.add(new Models.TagMapping("精选", "/精选"));
            setField(activity, "classificationMode", true);
            setField(activity, "currentIndex", 0);
            setField(activity, "currentPage", 0);
            invoke(activity, "refreshTags");
            assertTrue(findCheckBox(activity.findViewById(android.R.id.content), "归档").isChecked());
            assertFalse(findCheckBox(activity.findViewById(android.R.id.content), "精选").isChecked());
            Method setPage = MainActivity.class.getDeclaredMethod("setPage", int.class);
            setPage.setAccessible(true);
            setPage.invoke(activity, 1);
            assertEquals(20, getField(activity, "currentIndex"));
            assertFalse(findCheckBox(activity.findViewById(android.R.id.content), "归档").isChecked());
            assertTrue(findCheckBox(activity.findViewById(android.R.id.content), "精选").isChecked());
        }
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-mdpi")
    public void performanceSettingsRenderOffline() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            showMainScreen(activity);
            invoke(activity, "showPerformanceSettings");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Dialog dialog = ShadowDialog.getLatestDialog();
            assertNotNull(dialog);
            View dialogDecor = dialog.getWindow().getDecorView();
            dialogDecor.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.AT_MOST));
            int dialogHeight = Math.max(1, Math.min(360, dialogDecor.getMeasuredHeight()));
            renderViewAndVerify(dialogDecor, "settings-landscape.png", 640, dialogHeight);
            assertNotNull(findText(dialogDecor, "性能与缓存"));
            assertNotNull(findText(dialogDecor, "分页预热并发数"));
            assertNotNull(findText(dialogDecor, "批量提交并发数"));
        }
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-mdpi")
    public void mainPhoneLandscapeUsesThreeColumnWorkspace() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            showMainScreen(activity);
            populateClassificationState(activity);
            renderAndVerify(activity, "main-phone-landscape.png", 640, 360);
            Rect files = panelBounds(activity, "当前目录文件");
            Rect preview = panelBounds(activity, "年度报告.pdf");
            Rect tags = panelBounds(activity, "分类标签");
            assertTrue(files.left < preview.left);
            assertTrue(preview.left < tags.left);
            assertNotNull(findText(activity.findViewById(android.R.id.content), "/待分类"));
            assertEquals(0, countVisibleTextButtons(
                    activity.findViewById(android.R.id.content), "选择目录"));
            assertTouchTargets(activity.findViewById(android.R.id.content), 48);
        }
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-mdpi")
    public void mainLandscapeUsesThreeColumnWorkspace() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            showMainScreen(activity);
            populateClassificationState(activity);
            renderAndVerify(activity, "main-landscape.png", 891, 411);
            Rect files = panelBounds(activity, "当前目录文件");
            Rect preview = panelBounds(activity, "年度报告.pdf");
            Rect tags = panelBounds(activity, "分类标签");
            assertTrue(files.left < preview.left);
            assertTrue(preview.left < tags.left);
            TextView previewInfo = (TextView) getField(activity, "previewInfo");
            assertTrue(previewInfo.getText().toString().contains("路径: /待分类/年度报告.pdf"));
            assertFalse(previewInfo.getParent() instanceof FrameLayout);
            assertTouchTargets(activity.findViewById(android.R.id.content), 48);
        }
    }

    @Test
    @Config(qualifiers = "w1000dp-h700dp-land-mdpi")
    public void mainTabletUsesThreeColumnWorkspace() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            showMainScreen(activity);
            populateClassificationState(activity);
            renderAndVerify(activity, "main-tablet.png", 1000, 700);
            Rect files = panelBounds(activity, "当前目录文件");
            Rect preview = panelBounds(activity, "年度报告.pdf");
            Rect tags = panelBounds(activity, "分类标签");
            assertTrue(files.left < preview.left);
            assertTrue(preview.left < tags.left);
            assertTouchTargets(activity.findViewById(android.R.id.content), 48);
        }
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-mdpi")
    public void rotationKeepsBusyClassificationLocked() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            showMainScreen(activity);
            populateClassificationState(activity);
            setField(activity, "busy", true);
            activity.onConfigurationChanged(new Configuration(activity.getResources().getConfiguration()));
            Button enter = (Button) getField(activity, "enterButton");
            assertFalse(enter.isEnabled());
            invoke(activity, "exitClassificationMode");
            assertTrue((Boolean) getField(activity, "classificationMode"));
            assertEquals(3, ((ArrayList<?>) getField(activity, "files")).size());
        }
    }

    @Test
    public void identicalConvertedOutputsAreSerialized() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            AtomicInteger firstRuns = new AtomicInteger();
            AtomicInteger secondRuns = new AtomicInteger();
            Method enqueue = MainActivity.class.getDeclaredMethod(
                    "enqueueConvertedUpload", String.class, Runnable.class);
            enqueue.setAccessible(true);
            enqueue.invoke(activity, "same.webp", (Runnable) firstRuns::incrementAndGet);
            enqueue.invoke(activity, "same.webp", (Runnable) secondRuns::incrementAndGet);
            assertEquals(1, firstRuns.get());
            assertEquals(0, secondRuns.get());
            Method finish = MainActivity.class.getDeclaredMethod("finishConvertedUpload", String.class);
            finish.setAccessible(true);
            finish.invoke(activity, "same.webp");
            assertEquals(1, secondRuns.get());
        }
    }

    @SuppressWarnings("unchecked")
    private static void populateClassificationState(MainActivity activity) throws Exception {
        Field filesField = MainActivity.class.getDeclaredField("files");
        filesField.setAccessible(true);
        ArrayList<Models.FileItem> files = (ArrayList<Models.FileItem>) filesField.get(activity);
        files.clear();
        Models.FileItem current = fileItem("年度报告.pdf", "/待分类/年度报告.pdf", 2_400_000L);
        Models.FileItem pending = fileItem("旅行照片.heic", "/待分类/旅行照片.heic", 18_900_000L);
        pending.selectedTags.add("归档");
        Models.FileItem submitted = fileItem("封面.jpg", "/待分类/封面.jpg", 4_100_000L);
        submitted.submitted = true;
        files.add(current);
        files.add(pending);
        files.add(submitted);

        Field tagsField = MainActivity.class.getDeclaredField("tags");
        tagsField.setAccessible(true);
        ArrayList<Models.TagMapping> tags = (ArrayList<Models.TagMapping>) tagsField.get(activity);
        tags.clear();
        tags.add(new Models.TagMapping("归档", "/图片/归档"));
        tags.add(new Models.TagMapping("精选", "/图片/精选"));

        setField(activity, "classificationMode", true);
        setField(activity, "currentIndex", 0);
        setField(activity, "currentPage", 0);
        setField(activity, "sourcePath", "/待分类");
        ((TextView) getField(activity, "sourcePathView")).setText("/待分类");
        ((TextView) getField(activity, "visiblePathView")).setText("/待分类");
        invoke(activity, "updateClassificationButton");
        invoke(activity, "renderFileList");
        invoke(activity, "renderCurrentFile");
        invoke(activity, "refreshTags");
        invoke(activity, "updateStats");
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static Models.FileItem fileItem(String name, String path, long size) {
        Models.RemoteEntry entry = new Models.RemoteEntry();
        entry.name = name;
        entry.path = path;
        entry.size = size;
        entry.modified = "2026-08-19 16:30:00";
        return new Models.FileItem(entry);
    }

    private static Object getField(MainActivity activity, String fieldName) throws Exception {
        Field field = MainActivity.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(activity);
    }

    private static Object getField(PreviewActivity activity, String fieldName) throws Exception {
        Field field = PreviewActivity.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(activity);
    }

    private static void setField(MainActivity activity, String fieldName, Object value) throws Exception {
        Field field = MainActivity.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(activity, value);
    }

    private static void showMainScreen(MainActivity activity) throws Exception {
        invoke(activity, "showMainScreen");
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void invoke(MainActivity activity, String methodName) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(activity);
    }

    private static void renderAndVerify(MainActivity activity, String name, int width, int height)
            throws Exception {
        renderViewAndVerify(activity.getWindow().getDecorView(), name, width, height);
    }

    private static void renderViewAndVerify(View decor, String name, int width, int height)
            throws Exception {
        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY);
        decor.measure(widthSpec, heightSpec);
        decor.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xffffffff);
        decor.draw(new Canvas(bitmap));

        HashSet<Integer> colors = new HashSet<>();
        int nonBackground = 0;
        int background = bitmap.getPixel(0, 0);
        for (int y = 0; y < height; y += 4) {
            for (int x = 0; x < width; x += 4) {
                int color = bitmap.getPixel(x, y);
                colors.add(color);
                if (color != background) nonBackground++;
            }
        }
        assertTrue("offline render has too few colors", colors.size() > 40);
        assertTrue("offline render appears blank", nonBackground > (width * height / 16) / 20);

        File directory = new File(System.getProperty("user.dir"), "build/reports/screenshots");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File output = new File(directory, name);
        try (FileOutputStream stream = new FileOutputStream(output)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
        assertTrue("screenshot output is unexpectedly small", output.length() > 3000L);
        bitmap.recycle();
    }

    private static Rect panelBounds(MainActivity activity, String heading) {
        TextView title = findText(activity.findViewById(android.R.id.content), heading);
        assertNotNull("missing panel heading: " + heading, title);
        View current = title;
        while (current != null && !(current instanceof MaterialCardView)) {
            if (!(current.getParent() instanceof View)) break;
            current = (View) current.getParent();
        }
        assertTrue("heading is not inside a Material panel: " + heading,
                current instanceof MaterialCardView);
        int[] location = new int[2];
        current.getLocationOnScreen(location);
        return new Rect(location[0], location[1], location[0] + current.getWidth(),
                location[1] + current.getHeight());
    }

    private static TextView findText(View view, String expected) {
        if (view instanceof TextView && expected.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                TextView found = findText(group.getChildAt(index), expected);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findDescription(View view, String description) {
        if (view.getContentDescription() != null
                && description.contentEquals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = findDescription(group.getChildAt(index), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static CheckBox findCheckBox(View view, String label) {
        if (view instanceof CheckBox && label.contentEquals(((CheckBox) view).getText())) {
            return (CheckBox) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                CheckBox found = findCheckBox(group.getChildAt(index), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static int countVisibleTextButtons(View view, String text) {
        if (view.getVisibility() != View.VISIBLE) return 0;
        int count = view instanceof MaterialButton
                && text.contentEquals(((MaterialButton) view).getText()) ? 1 : 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                count += countVisibleTextButtons(group.getChildAt(index), text);
            }
        }
        return count;
    }

    private static void assertTouchTargets(View view, int minimumDp) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof Button) {
            assertTrue("button is shorter than " + minimumDp + "dp: " + ((Button) view).getText(),
                    view.getMeasuredHeight() >= minimumDp);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                assertTouchTargets(group.getChildAt(index), minimumDp);
            }
        }
    }
}
