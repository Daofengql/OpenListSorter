package com.openlistsorter.app.network;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.openlistsorter.app.data.Models;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import javax.net.ssl.SSLException;

public final class OpenListClient {
    private static final String TAG = "OpenListClient";
    private static final int MAX_DIRECTORY_ENTRIES = 2000;
    public interface SimpleCallback {
        void onResult(boolean ok, String message);
    }

    public interface StageCallback {
        void onStage(String message);
    }

    public interface ListCallback {
        void onResult(boolean ok, String message, String path, ArrayList<Models.RemoteEntry> entries);
    }

    public interface EntryCallback {
        void onResult(boolean ok, String message, Models.RemoteEntry entry);
    }

    public interface DownloadCallback {
        void onResult(boolean ok, String message, byte[] data);
    }

    public interface ProgressCallback {
        void onProgress(long received, long total);
    }

    public static final class ConnectionDiagnostics {
        public String endpoint = "";
        public boolean connected;
        public boolean publicSettingsOk;
        public long latencyMs = -1;
        public String ipAddress = "";
        public String version = "";
        public String siteTitle = "";
        public String message = "";
    }

    private interface JsonCallback {
        void onResult(boolean ok, String message, JSONObject data);
    }

    private static final class HttpResult {
        int status;
        byte[] body = new byte[0];
        String error = "";
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(6, runnable -> {
        Thread thread = new Thread(runnable, "openlist-io");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final ExecutorService preloadExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "openlist-preload");
        thread.setPriority(Thread.NORM_PRIORITY - 2);
        return thread;
    });
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            // Preserve large transfers while preventing drip connections from occupying a worker forever.
            .callTimeout(30, TimeUnit.MINUTES)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();
    private volatile boolean closed;
    private volatile String baseUrl = "";
    private volatile String token = "";

    public void setBaseUrl(String value) {
        baseUrl = sanitizeBaseUrl(value);
    }

    public void setToken(String value) {
        token = value == null ? "" : value.trim();
    }

    public void clearConnection() {
        baseUrl = "";
        token = "";
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public boolean hasConnection() {
        return !baseUrl.isEmpty() && !token.isEmpty();
    }

    public void login(String endpoint, String username, String password, SimpleCallback callback) {
        login(endpoint, username, password, null, callback);
    }

    public void login(String endpoint, String username, String password, StageCallback stage,
                      SimpleCallback callback) {
        setBaseUrl(endpoint);
        if (stage != null) stage.onStage("正在准备账号登录...");
        token = "";
        JSONObject payload = new JSONObject();
        try {
            payload.put("username", username);
            payload.put("password", password);
        } catch (JSONException error) {
            post(callback, false, error.getMessage());
            return;
        }
        postJson("/api/auth/login", payload, (ok, message, data) -> {
            if (ok) {
                String newToken = data.optString("token", "");
                if (newToken.isEmpty()) {
                    post(callback, false, "登录成功但没有返回 token");
                    return;
                }
                token = newToken;
            }
            post(callback, ok, message);
        });
    }

    public void validateToken(String endpoint, String tokenValue, SimpleCallback callback) {
        validateToken(endpoint, tokenValue, null, callback);
    }

    public void validateToken(String endpoint, String tokenValue, StageCallback stage,
                              SimpleCallback callback) {
        setBaseUrl(endpoint);
        setToken(tokenValue);
        if (stage != null) stage.onStage("正在测试服务器地址...");
        probeEndpoint((serverOk, serverMessage) -> {
            if (!serverOk) {
                callback.onResult(false, "服务器地址测试失败: " + serverMessage);
                return;
            }
            if (stage != null) stage.onStage("服务器已响应，正在读取根目录验证 token...");
            listDirectory("/", false, (ok, message, path, entries) ->
                    callback.onResult(ok, ok ? "token 可用" : "token 验证失败: " + message));
        });
    }

    private void probeEndpoint(SimpleCallback callback) {
        try {
            executor.execute(() -> {
                HttpResult result = request("GET", "/api/public/settings", null, null);
                if (!result.error.isEmpty()) {
                    post(callback, false, result.error);
                } else {
                    post(callback, true, "服务器已响应 HTTP " + result.status);
                }
            });
        } catch (RejectedExecutionException error) {
            post(callback, false, "请求线程池已关闭，请重试");
        }
    }

    public void fetchConnectionDiagnostics(java.util.function.Consumer<ConnectionDiagnostics> callback) {
        ConnectionDiagnostics diagnostics = new ConnectionDiagnostics();
        diagnostics.endpoint = baseUrl;
        diagnostics.connected = hasConnection();
        if (baseUrl.isEmpty()) {
            post(callback, diagnostics);
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    long started = System.currentTimeMillis();
                    HttpResult result = request("GET", "/api/public/settings", null, null);
                    diagnostics.latencyMs = System.currentTimeMillis() - started;
                    if (result.error.isEmpty()) {
                        try {
                            JSONObject root = new JSONObject(new String(result.body, StandardCharsets.UTF_8));
                            diagnostics.publicSettingsOk = result.status >= 200 && result.status < 300
                                    && root.optInt("code", -1) == 200;
                            diagnostics.message = firstNonEmpty(root.optString("message", ""), root.optString("msg", ""));
                            JSONObject data = root.optJSONObject("data");
                            if (data != null) {
                                diagnostics.version = firstNonEmpty(data.optString("version", ""), data.optString("Version", ""));
                                diagnostics.siteTitle = firstNonEmpty(data.optString("site_title", ""),
                                        firstNonEmpty(data.optString("siteTitle", ""), data.optString("title", "")));
                                JSONObject settings = data.optJSONObject("settings");
                                if (settings != null) {
                                    diagnostics.version = firstNonEmpty(diagnostics.version,
                                            firstNonEmpty(settings.optString("version", ""), settings.optString("Version", "")));
                                    diagnostics.siteTitle = firstNonEmpty(diagnostics.siteTitle,
                                            firstNonEmpty(settings.optString("site_title", ""), settings.optString("title", "")));
                                }
                            }
                        } catch (JSONException error) {
                            diagnostics.message = error.getMessage();
                        }
                    } else {
                        diagnostics.message = result.error;
                    }
                    try {
                        URI uri = URI.create(baseUrl);
                        diagnostics.ipAddress = InetAddress.getByName(uri.getHost()).getHostAddress();
                    } catch (Exception ignored) {
                        diagnostics.ipAddress = "未知";
                    }
                    post(callback, diagnostics);
                } catch (Throwable throwable) {
                    Log.e(TAG, "fetchConnectionDiagnostics crashed", throwable);
                    if (diagnostics.message.isEmpty()) {
                        diagnostics.message = "检测异常: " + throwable;
                    }
                    post(callback, diagnostics);
                }
            });
        } catch (RejectedExecutionException error) {
            if (diagnostics.message.isEmpty()) {
                diagnostics.message = "检测线程池已关闭，请重试";
            }
            post(callback, diagnostics);
        }
    }

    public void listDirectory(String path, boolean refresh, ListCallback callback) {
        String normalized = Models.normalizeRemotePath(path);
        JSONObject payload = new JSONObject();
        try {
            payload.put("path", normalized);
            payload.put("password", "");
            payload.put("page", 1);
            // Do not ask the server for an unbounded directory response. Very large
            // directories otherwise create a huge JSON object and a matching object graph.
            payload.put("per_page", MAX_DIRECTORY_ENTRIES);
            payload.put("refresh", refresh);
        } catch (JSONException error) {
            post(callback, false, error.getMessage(), normalized, new ArrayList<>());
            return;
        }
        postJson("/api/fs/list", payload, (ok, message, data) -> {
            ArrayList<Models.RemoteEntry> entries = new ArrayList<>();
            if (ok) {
                JSONArray content = data.optJSONArray("content");
                if (content != null) {
                    int limit = Math.min(content.length(), MAX_DIRECTORY_ENTRIES);
                    for (int i = 0; i < limit; i++) {
                        JSONObject object = content.optJSONObject(i);
                        if (object != null) {
                            entries.add(Models.RemoteEntry.fromJson(object, normalized));
                        }
                    }
                    if (content.length() > MAX_DIRECTORY_ENTRIES) {
                        message = message + "；目录过大，仅加载前 " + MAX_DIRECTORY_ENTRIES + " 项";
                    }
                }
            }
            callback.onResult(ok, message, normalized, entries);
        });
    }

    public void getFileInfo(String path, EntryCallback callback) {
        String normalized = Models.normalizeRemotePath(path);
        JSONObject payload = new JSONObject();
        try {
            payload.put("path", normalized);
            payload.put("password", "");
        } catch (JSONException error) {
            post(callback, false, error.getMessage(), new Models.RemoteEntry());
            return;
        }
        postJson("/api/fs/get", payload, (ok, message, data) -> {
            Models.RemoteEntry entry = new Models.RemoteEntry();
            if (ok) {
                entry = Models.RemoteEntry.fromJson(data, Models.parentRemotePath(normalized));
                entry.path = normalized;
                entry.rawUrl = data.optString("raw_url", "");
            }
            callback.onResult(ok, message, entry);
        });
    }

    public void downloadUrl(URL url, DownloadCallback callback, ProgressCallback progressCallback) {
        downloadUrl(url, callback, progressCallback, false);
    }

    public void downloadUrl(URL url, DownloadCallback callback, ProgressCallback progressCallback,
                            boolean lowPriority) {
        if (url == null) {
            post(callback, false, "文件下载地址无效。", new byte[0]);
            return;
        }
        try {
            ExecutorService downloadExecutor = lowPriority ? preloadExecutor : executor;
            downloadExecutor.execute(() -> {
                try {
                    HttpResult result = requestAbsolute(url, progressCallback);
                    if (!result.error.isEmpty()) {
                        post(callback, false, result.error, new byte[0]);
                    } else if (result.status < 200 || result.status >= 300) {
                        post(callback, false, "下载 HTTP 状态码 " + result.status, result.body);
                    } else {
                        post(callback, true, "success", result.body);
                    }
                } catch (Throwable throwable) {
                    Log.e(TAG, "downloadUrl(" + url + ") crashed", throwable);
                    post(callback, false, "下载异常: " + throwable, new byte[0]);
                }
            });
        } catch (RejectedExecutionException error) {
            post(callback, false, "下载线程池已关闭，请重试", new byte[0]);
        }
    }

    public void uploadFile(String remotePath, byte[] data, boolean overwrite, SimpleCallback callback) {
        String normalized = Models.normalizeRemotePath(remotePath);
        java.util.HashMap<String, String> headers = new java.util.HashMap<>();
        headers.put("File-Path", percentEncodePath(normalized));
        headers.put("As-Task", "false");
        headers.put("Overwrite", overwrite ? "true" : "false");
        try {
            executor.execute(() -> {
                try {
                    HttpResult result = request("PUT", "/api/fs/put", data, headers);
                    if (!result.error.isEmpty()) {
                        post(callback, false, result.error);
                        return;
                    }
                    ApiResponse api = parseApi(result.body);
                    post(callback, result.status >= 200 && result.status < 300 && api.ok, api.message);
                } catch (Throwable throwable) {
                    Log.e(TAG, "uploadFile(" + normalized + ") crashed", throwable);
                    post(callback, false, "上传异常: " + throwable);
                }
            });
        } catch (RejectedExecutionException error) {
            post(callback, false, "上传线程池已关闭，请重试");
        }
    }

    public void removeFile(String directory, String fileName, SimpleCallback callback) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("dir", Models.normalizeRemotePath(directory));
            JSONArray names = new JSONArray();
            names.put(fileName);
            payload.put("names", names);
        } catch (JSONException error) {
            post(callback, false, error.getMessage());
            return;
        }
        postJson("/api/fs/remove", payload, (ok, message, data) ->
                post(callback, ok, firstNonEmpty(data.optString("message", ""), message)));
    }

    public void moveFile(String sourceDirectory, String fileName, String targetDirectory,
                         boolean overwrite, SimpleCallback callback) {
        moveOrCopy("/api/fs/move", sourceDirectory, fileName, targetDirectory, overwrite, callback);
    }

    public void copyFile(String sourceDirectory, String fileName, String targetDirectory,
                         boolean overwrite, SimpleCallback callback) {
        moveOrCopy("/api/fs/copy", sourceDirectory, fileName, targetDirectory, overwrite, callback);
    }

    public static URL resolveUrl(String base, String value) {
        try {
            URL url = new URL(value);
            if (url.getProtocol() != null && url.getHost() != null && !url.getHost().isEmpty()) {
                return url;
            }
        } catch (Exception ignored) {
        }
        try {
            String root = sanitizeBaseUrl(base) + "/";
            return new URL(new URL(root), value);
        } catch (Exception error) {
            return null;
        }
    }

    public void shutdown() {
        if (closed) {
            return;
        }
        closed = true;
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        preloadExecutor.shutdownNow();
        httpClient.dispatcher().cancelAll();
        // OkHttp may write a TLS close-notify while evicting an idle connection.
        // That is a network operation and must not run from Activity.onDestroy().
        Thread cleanupThread = new Thread(() -> {
            try {
                httpClient.connectionPool().evictAll();
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to evict HTTP connections during shutdown", error);
            }
        }, "openlist-close");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    private void moveOrCopy(String endpoint, String sourceDirectory, String fileName,
                            String targetDirectory, boolean overwrite, SimpleCallback callback) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("src_dir", Models.normalizeRemotePath(sourceDirectory));
            payload.put("dst_dir", Models.normalizeRemotePath(targetDirectory));
            payload.put("overwrite", overwrite);
            payload.put("skip_existing", false);
            payload.put("merge", false);
            JSONArray names = new JSONArray();
            names.put(fileName);
            payload.put("names", names);
        } catch (JSONException error) {
            post(callback, false, error.getMessage());
            return;
        }
        postJson(endpoint, payload, (ok, message, data) ->
                post(callback, ok, firstNonEmpty(data.optString("message", ""), message)));
    }

    private void postJson(String endpoint, JSONObject payload, JsonCallback callback) {
        try {
            executor.execute(() -> {
                try {
                    HttpResult result = request("POST", endpoint,
                            payload.toString().getBytes(StandardCharsets.UTF_8), null);
                    if (!result.error.isEmpty()) {
                        post(callback, false, result.error, new JSONObject());
                        return;
                    }
                    if (result.status < 200 || result.status >= 300) {
                        ApiResponse api = parseApi(result.body);
                        String detail = api.message;
                        post(callback, false, "服务器返回 HTTP " + result.status
                                + (detail.isEmpty() ? "" : ": " + detail), new JSONObject());
                        return;
                    }
                    ApiResponse api = parseApi(result.body);
                    post(callback, api.ok, api.message, api.data);
                } catch (Throwable throwable) {
                    Log.e(TAG, "postJson(" + endpoint + ") crashed", throwable);
                    post(callback, false, "请求异常: " + throwable, new JSONObject());
                }
            });
        } catch (RejectedExecutionException error) {
            post(callback, false, "请求线程池已关闭，请重试", new JSONObject());
        }
    }

    private HttpResult request(String method, String endpoint, byte[] body,
                               java.util.Map<String, String> extraHeaders) {
        HttpResult result = new HttpResult();
        if (baseUrl.isEmpty()) {
            result.error = "OpenList 地址为空";
            return result;
        }
        try {
            return requestAbsolute(new URL(baseUrl + endpoint), method, body, extraHeaders, null);
        } catch (Exception error) {
            result.error = error.getMessage() == null ? error.toString() : error.getMessage();
            return result;
        }
    }

    private HttpResult requestAbsolute(URL url, ProgressCallback progressCallback) {
        return requestAbsolute(url, "GET", null, null, progressCallback);
    }

    private HttpResult requestAbsolute(URL url, String method, byte[] body,
                                       java.util.Map<String, String> extraHeaders,
                                       ProgressCallback progressCallback) {
        HttpResult result = new HttpResult();
        try {
            Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "OpenListSorter/1.0");
            if (!token.isEmpty() && sameOrigin(url, new URL(baseUrl))) {
                requestBuilder.header("Authorization", token);
            }
            MediaType mediaType = MediaType.parse("application/json");
            if (extraHeaders != null) {
                for (java.util.Map.Entry<String, String> header : extraHeaders.entrySet()) {
                    requestBuilder.header(header.getKey(), header.getValue());
                }
                if (extraHeaders.containsKey("File-Path")) {
                    mediaType = MediaType.parse("application/octet-stream");
                    requestBuilder.header("Accept", "application/json");
                }
            }
            RequestBody requestBody = body == null ? null : RequestBody.create(body, mediaType);
            if (body == null && "GET".equalsIgnoreCase(method)) {
                requestBuilder.get();
            } else {
                requestBuilder.method(method, requestBody);
            }
            try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
                result.status = response.code();
                ResponseBody responseBody = response.body();
                if (responseBody != null) {
                    result.body = progressCallback == null
                            ? responseBody.bytes()
                            : readAll(responseBody.byteStream(), responseBody.contentLength(),
                                    progressCallback);
                }
                Log.d(TAG, method + " " + url + " -> HTTP " + result.status);
            }
        } catch (Exception error) {
            result.error = friendlyError(error, url);
            Log.w(TAG, method + " " + url + " failed: " + result.error, error);
        }
        return result;
    }

    private static String friendlyError(Exception error, URL url) {
        String raw = error.getMessage() == null ? error.toString() : error.getMessage();
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.US);
        if (error instanceof ConnectException
                && ("localhost".equals(host) || "127.0.0.1".equals(host)
                || "0.0.0.0".equals(host))) {
            return "Android 设备上的 " + host + " 指向设备本身，不是电脑上的 OpenList；请改用电脑局域网 IP。";
        }
        if (error instanceof UnknownHostException) {
            return "无法解析服务器域名（DNS 失败）: " + raw;
        }
        if (error instanceof ConnectException) {
            return "无法连接到服务器（连接被拒绝，请检查地址、端口和防火墙）: " + raw;
        }
        if (error instanceof SocketTimeoutException) {
            return "连接或读取超时，请检查网络和服务器状态: " + raw;
        }
        if (error instanceof MalformedURLException) {
            return "服务器地址格式不正确: " + raw;
        }
        if (error instanceof SSLException) {
            return "TLS/证书握手失败（自签名证书需先安装到设备或改用 http://）: " + raw;
        }
        return raw;
    }

    private byte[] readAll(InputStream input, long total, ProgressCallback progressCallback) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[65536];
            long received = 0;
            while (total < 0 || received < total) {
                int requested = buffer.length;
                if (total >= 0) {
                    requested = (int) Math.min(requested, total - received);
                }
                int count = stream.read(buffer, 0, requested);
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    continue;
                }
                output.write(buffer, 0, count);
                received += count;
                if (!closed && progressCallback != null) {
                    progressCallback.onProgress(received, total);
                }
            }
            return output.toByteArray();
        }
    }

    private static boolean sameOrigin(URL left, URL right) {
        int leftPort = left.getPort() >= 0 ? left.getPort() : left.getDefaultPort();
        int rightPort = right.getPort() >= 0 ? right.getPort() : right.getDefaultPort();
        return left.getProtocol().equalsIgnoreCase(right.getProtocol())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && leftPort == rightPort;
    }

    private static final class ApiResponse {
        boolean ok;
        String message = "";
        JSONObject data = new JSONObject();
    }

    private static ApiResponse parseApi(byte[] payload) {
        ApiResponse result = new ApiResponse();
        try {
            JSONObject root = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            int code = root.optInt("code", -1);
            result.message = firstNonEmpty(root.optString("message", ""), root.optString("msg", ""));
            Object value = root.opt("data");
            if (value instanceof JSONObject) {
                result.data = (JSONObject) value;
            } else if (value != null && value != JSONObject.NULL) {
                result.data = new JSONObject().put("value", value);
            }
            result.ok = code == 200;
            if (result.message.isEmpty()) {
                result.message = result.ok ? "success" : "OpenList API 返回错误码 " + code;
            }
        } catch (JSONException error) {
            result.message = "无法解析 OpenList 返回内容: " + error.getMessage();
        }
        return result;
    }

    private static String percentEncodePath(String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder(utf8.length * 3);
        final char[] hex = "0123456789ABCDEF".toCharArray();
        for (byte item : utf8) {
            int current = item & 0xff;
            boolean unreserved = (current >= 'a' && current <= 'z')
                    || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9')
                    || current == '-' || current == '_' || current == '.' || current == '~';
            if (unreserved) {
                result.append((char) current);
            } else {
                result.append('%').append(hex[current >>> 4]).append(hex[current & 0x0f]);
            }
        }
        return result.toString();
    }

    private static String sanitizeBaseUrl(String value) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty()) {
            return "";
        }
        String lower = result.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            int schemeEnd = result.indexOf("://");
            if (schemeEnd > 0) {
                result = result.substring(schemeEnd + 3);
            }
            result = "http://" + result;
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String firstNonEmpty(String first, String second) {
        return first == null || first.isEmpty() ? (second == null ? "" : second) : first;
    }

    private void runOnMain(Runnable callback) {
        if (closed) {
            return;
        }
        mainHandler.post(() -> {
            if (!closed) {
                callback.run();
            }
        });
    }

    private void post(SimpleCallback callback, boolean ok, String message) {
        runOnMain(() -> callback.onResult(ok, message == null ? "" : message));
    }

    private void post(ListCallback callback, boolean ok, String message, String path,
                      ArrayList<Models.RemoteEntry> entries) {
        runOnMain(() -> callback.onResult(ok, message == null ? "" : message, path, entries));
    }

    private void post(EntryCallback callback, boolean ok, String message, Models.RemoteEntry entry) {
        runOnMain(() -> callback.onResult(ok, message == null ? "" : message, entry));
    }

    private void post(DownloadCallback callback, boolean ok, String message, byte[] data) {
        runOnMain(() -> callback.onResult(ok, message == null ? "" : message, data));
    }

    private void post(JsonCallback callback, boolean ok, String message, JSONObject data) {
        runOnMain(() -> callback.onResult(ok, message == null ? "" : message, data));
    }

    private void post(java.util.function.Consumer<ConnectionDiagnostics> callback,
                      ConnectionDiagnostics diagnostics) {
        runOnMain(() -> callback.accept(diagnostics));
    }
}
