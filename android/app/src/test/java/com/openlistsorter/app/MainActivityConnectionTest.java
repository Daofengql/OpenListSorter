package com.openlistsorter.app;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.openlistsorter.app.data.ConnectionStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class MainActivityConnectionTest {
    private ServerSocket server;
    private Thread serverThread;

    @Before
    public void setUp() throws IOException {
        Context context = RuntimeEnvironment.getApplication();
        ConnectionStore.clearRememberedConnection(context);
        server = new ServerSocket(0);
        serverThread = new Thread(this::serveRequests, "activity-openlist-server");
        serverThread.start();
    }

    @After
    public void tearDown() throws IOException, InterruptedException {
        if (server != null) server.close();
        if (serverThread != null) serverThread.join(2000L);
    }

    @Test
    public void successfulTokenValidationDisplaysMainScreen() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            field(activity, "endpointEdit", EditText.class)
                    .setText("http://127.0.0.2:" + server.getLocalPort());
            field(activity, "tokenEdit", EditText.class).setText("token-abc");
            field(activity, "connectButton", Button.class).performClick();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline
                    && !hasText(activity.findViewById(android.R.id.content), "分类任务")) {
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                Thread.sleep(20L);
            }
            assertTrue("successful connection did not replace the connection screen",
                    hasText(activity.findViewById(android.R.id.content), "分类任务"));
        }
    }

    private void serveRequests() {
        try {
            for (int index = 0; index < 2; index++) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    readRequest(socket);
                    byte[] response = "{\"code\":200,\"message\":\"success\",\"data\":{\"content\":[]}}"
                            .getBytes(StandardCharsets.UTF_8);
                    OutputStream output = socket.getOutputStream();
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                            + response.length + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.ISO_8859_1));
                    output.write(response);
                    output.flush();
                }
            }
        } catch (Exception ignored) {
            // Test assertions report a missing main screen if the mock fails.
        }
    }

    private static void readRequest(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.ISO_8859_1));
        int contentLength = 0;
        reader.readLine();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0 && "Content-Length".equalsIgnoreCase(line.substring(0, colon).trim())) {
                contentLength = Integer.parseInt(line.substring(colon + 1).trim());
            }
        }
        for (int read = 0; read < contentLength; ) {
            int count = reader.read(new char[Math.min(1024, contentLength - read)]);
            if (count < 0) break;
            read += count;
        }
    }

    private static boolean hasText(View view, String expected) {
        if (view instanceof TextView && expected.contentEquals(((TextView) view).getText())) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                if (hasText(group.getChildAt(index), expected)) return true;
            }
        }
        return false;
    }

    private static <T> T field(MainActivity activity, String name, Class<T> type) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(activity));
    }
}
