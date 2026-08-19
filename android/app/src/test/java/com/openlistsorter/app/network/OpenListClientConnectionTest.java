package com.openlistsorter.app.network;

import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class OpenListClientConnectionTest {
    private ServerSocket server;
    private Thread serverThread;
    private final CountDownLatch requestSeen = new CountDownLatch(1);
    private final AtomicBoolean requestValid = new AtomicBoolean();
    private final AtomicReference<String> requestDetails = new AtomicReference<>("");

    @Before
    public void startServer() throws IOException {
        server = new ServerSocket(0);
        serverThread = new Thread(this::serveRequests, "openlist-test-server");
        serverThread.start();
    }

    @After
    public void stopServer() throws IOException, InterruptedException {
        if (server != null) server.close();
        if (serverThread != null) serverThread.join(2000L);
    }

    @Test
    public void tokenValidationSendsQtCompatibleRequest() throws Exception {
        CountDownLatch callbackSeen = new CountDownLatch(1);
        AtomicReference<Boolean> callbackOk = new AtomicReference<>();
        OpenListClient client = new OpenListClient();
        try {
            client.validateToken("http://127.0.0.1:" + server.getLocalPort(), "token-abc",
                    (ok, message) -> {
                        callbackOk.set(ok);
                        callbackSeen.countDown();
                    });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && callbackSeen.getCount() > 0) {
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                Thread.sleep(20L);
            }
            assertTrue("request was not sent", requestSeen.getCount() == 0);
            assertTrue("callback was not delivered", callbackSeen.getCount() == 0);
            assertTrue(requestDetails.get(), requestValid.get());
            assertEquals(Boolean.TRUE, callbackOk.get());
        } finally {
            client.shutdown();
        }
    }

    private void serveRequests() {
        try {
            for (int requestNumber = 0; requestNumber < 2; requestNumber++) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    String[] request = readRequest(socket);
                    String requestLine = request[0];
                    String authorization = request[1];
                    String body = request[2];
                    if (requestLine != null && requestLine.startsWith("POST /api/fs/list")) {
                        requestDetails.set("line=" + requestLine + ", auth=" + authorization
                                + ", body=" + body);
                        requestValid.set("token-abc".equals(authorization)
                                && body.contains("\"path\":")
                                && body.contains("\"page\":1")
                                && body.contains("\"per_page\":0"));
                        requestSeen.countDown();
                    }
                    writeJson(socket);
                }
            }
        } catch (Exception ignored) {
            requestSeen.countDown();
        }
    }

    private static String[] readRequest(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.ISO_8859_1));
        String requestLine = reader.readLine();
        String authorization = "";
        int contentLength = 0;
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if ("Authorization".equalsIgnoreCase(name)) authorization = value;
                if ("Content-Length".equalsIgnoreCase(name)) contentLength = Integer.parseInt(value);
            }
        }
        char[] bodyChars = new char[contentLength];
        int read = 0;
        while (read < contentLength) {
            int count = reader.read(bodyChars, read, contentLength - read);
            if (count < 0) break;
            read += count;
        }
        return new String[]{requestLine, authorization, new String(bodyChars, 0, read)};
    }

    private static void writeJson(Socket socket) throws IOException {
        byte[] response = "{\"code\":200,\"message\":\"success\",\"data\":{\"content\":[]}}"
                .getBytes(StandardCharsets.UTF_8);
        OutputStream output = socket.getOutputStream();
        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n"
                .getBytes(StandardCharsets.ISO_8859_1));
        output.write(response);
        output.flush();
    }
}
