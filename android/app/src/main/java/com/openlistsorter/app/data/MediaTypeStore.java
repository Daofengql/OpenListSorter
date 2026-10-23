package com.openlistsorter.app.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

/** Persists suffix overrides and updates the process-wide file classifier. */
public final class MediaTypeStore {
    private static final String PREFS = "media_types";
    private static final String IMAGE_SUFFIXES = "customImageSuffixes";
    private static final String VIDEO_SUFFIXES = "customVideoSuffixes";

    private MediaTypeStore() {}

    public static void load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Models.configureCustomSuffixes(prefs.getStringSet(IMAGE_SUFFIXES, Collections.emptySet()),
                prefs.getStringSet(VIDEO_SUFFIXES, Collections.emptySet()));
    }

    public static boolean save(Context context, String imageText, String videoText) {
        Set<String> images = parse(imageText);
        Set<String> videos = parse(videoText);
        Set<String> overlap = new java.util.HashSet<>(images);
        overlap.retainAll(videos);
        if (!overlap.isEmpty()) return false;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(IMAGE_SUFFIXES, images)
                .putStringSet(VIDEO_SUFFIXES, videos)
                .apply();
        Models.configureCustomSuffixes(images, videos);
        return true;
    }

    public static String imageText(Context context) {
        return join(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(IMAGE_SUFFIXES, Collections.emptySet()));
    }

    public static String videoText(Context context) {
        return join(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(VIDEO_SUFFIXES, Collections.emptySet()));
    }

    private static Set<String> parse(String text) {
        if (text == null) return Collections.emptySet();
        return Models.normalizedSuffixes(Arrays.asList(text.split("[,;\\s]+")));
    }

    private static String join(Set<String> values) {
        ArrayList<String> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return String.join(", ", sorted);
    }
}
