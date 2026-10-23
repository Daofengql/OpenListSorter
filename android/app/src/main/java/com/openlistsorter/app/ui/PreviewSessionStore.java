package com.openlistsorter.app.ui;

import android.content.Context;
import android.os.Parcel;

import com.openlistsorter.app.data.Models;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps large full-screen preview state inside the app process. Activity Intents only carry a
 * short session id, avoiding Binder transaction failures when many files/tags are selected.
 */
public final class PreviewSessionStore {
    public static final class Session {
        public final ArrayList<Models.PreviewItem> items;
        public final ArrayList<String> tagNames;
        public int index;

        Session(ArrayList<Models.PreviewItem> items, ArrayList<String> tagNames, int index) {
            this.items = items;
            this.tagNames = tagNames;
            this.index = index;
        }

        public String currentPath() {
            return index >= 0 && index < items.size() ? items.get(index).path : "";
        }
    }

    private static final HashMap<String, Session> SESSIONS = new HashMap<>();
    private static final int MAX_LIVE_SESSIONS = 2;
    private static final int SESSION_FORMAT_V2 = 0x50535632;
    private static final ExecutorService PERSIST_EXECUTOR = Executors.newSingleThreadExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "preview-session-store");
                thread.setPriority(Thread.MIN_PRIORITY);
                thread.setDaemon(true);
                return thread;
            });
    private static File storageDirectory;

    private PreviewSessionStore() {}

    public static synchronized void initialize(Context context) {
        if (storageDirectory != null || context == null) return;
        storageDirectory = new File(context.getCacheDir(), "preview-sessions");
        //noinspection ResultOfMethodCallIgnored
        storageDirectory.mkdirs();
    }

    public static synchronized String create(ArrayList<Models.PreviewItem> items,
                                             ArrayList<String> tagNames, int index) {
        while (SESSIONS.size() >= MAX_LIVE_SESSIONS) {
            String oldest = SESSIONS.keySet().iterator().next();
            SESSIONS.remove(oldest);
        }
        String id = UUID.randomUUID().toString();
        Session session = new Session(items, tagNames, index);
        SESSIONS.put(id, session);
        persistAsync(id, session);
        return id;
    }

    public static synchronized Session peek(String id) {
        if (id == null || id.isEmpty()) return null;
        Session session = SESSIONS.get(id);
        if (session != null) return session;
        session = readLocked(id);
        if (session != null) SESSIONS.put(id, session);
        return session;
    }

    public static synchronized Session consume(String id) {
        if (id == null || id.isEmpty()) return null;
        Session session = peek(id);
        SESSIONS.remove(id);
        deleteLocked(id);
        return session;
    }

    public static synchronized void discard(String id) {
        if (id != null) {
            SESSIONS.remove(id);
            deleteLocked(id);
        }
    }

    public static synchronized void update(String id, Session session) {
        if (id == null || id.isEmpty() || session == null) return;
        SESSIONS.put(id, session);
        persistAsync(id, session);
    }

    private static void persistAsync(String id, Session source) {
        try {
            PERSIST_EXECUTOR.execute(() -> {
                Session persisted;
                try {
                    // Copying a large marked set is allocation-heavy. Keep it away from the UI
                    // thread, particularly during the transition back from full-screen preview.
                    persisted = snapshot(source);
                } catch (RuntimeException | OutOfMemoryError ignored) {
                    return;
                }
                synchronized (PreviewSessionStore.class) {
                    if (!SESSIONS.containsKey(id)) return;
                    persistLocked(id, persisted);
                }
            });
        } catch (RuntimeException ignored) {
            // The in-memory session remains authoritative if the process is shutting down.
        }
    }

    private static Session snapshot(Session source) {
        ArrayList<Models.PreviewItem> items = new ArrayList<>(source.items.size());
        for (Models.PreviewItem item : source.items) {
            Models.RemoteEntry entry = item.toEntry();
            Models.PreviewItem copy = new Models.PreviewItem(entry);
            copy.selectedTags.addAll(item.selectedTags);
            copy.tagsEditable = item.tagsEditable;
            copy.removed = item.removed;
            items.add(copy);
        }
        return new Session(items, new ArrayList<>(source.tagNames), source.index);
    }

    private static File fileForLocked(String id) {
        if (storageDirectory == null || id == null || id.isEmpty()) return null;
        return new File(storageDirectory, id + ".bin");
    }

    private static void persistLocked(String id, Session session) {
        File file = fileForLocked(id);
        if (file == null) return;
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeInt(SESSION_FORMAT_V2);
            parcel.writeTypedList(session.items);
            parcel.writeStringList(session.tagNames);
            parcel.writeInt(session.index);
            byte[] bytes = parcel.marshall();
            File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(bytes);
                output.flush();
            }
            if (!temporary.renameTo(file)) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                //noinspection ResultOfMethodCallIgnored
                temporary.renameTo(file);
            }
        } catch (RuntimeException | IOException ignored) {
            // The in-memory session remains authoritative when disk persistence is unavailable.
        } finally {
            parcel.recycle();
        }
    }

    private static Session readLocked(String id) {
        File file = fileForLocked(id);
        if (file == null || !file.isFile()) return null;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) Math.min(Integer.MAX_VALUE, file.length())];
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) break;
                offset += count;
            }
            if (offset != bytes.length) return null;
            Parcel parcel = Parcel.obtain();
            try {
                parcel.unmarshall(bytes, 0, bytes.length);
                parcel.setDataPosition(0);
                int format = parcel.readInt();
                ArrayList<Models.PreviewItem> items;
                if (format == SESSION_FORMAT_V2) {
                    items = parcel.createTypedArrayList(Models.PreviewItem.CREATOR);
                } else {
                    // Files from the pre-v2 app began directly with the typed-list size.
                    parcel.setDataPosition(0);
                    items = parcel.createTypedArrayList(Models.PreviewItem.LEGACY_CREATOR);
                }
                ArrayList<String> tags = parcel.createStringArrayList();
                int index = parcel.readInt();
                if (items == null) items = new ArrayList<>();
                if (tags == null) tags = new ArrayList<>();
                return new Session(items, tags, index);
            } finally {
                parcel.recycle();
            }
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static void deleteLocked(String id) {
        File file = fileForLocked(id);
        if (file != null) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            //noinspection ResultOfMethodCallIgnored
            new File(file.getPath() + ".tmp").delete();
        }
    }
}
