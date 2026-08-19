package com.openlistsorter.app.network;

import android.os.Handler;
import android.os.Looper;

import com.openlistsorter.app.data.Models;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class OpenListClient {
    public interface SimpleCallback {
        void onResult(boolean ok, String message);
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
    private final ExecutorService executor = Executors.newCachedThreadPool();
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
        setBaseUrl(endpoint);
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
        setBaseUrl(endpoint);
        setToken(tokenValue);
        listDirectory("/", false, (ok, message, path, entries) ->
                callback.onResult(ok, ok ? "token 可用" : message));
    }

    public void fetchConnectionDiagnostics(java.util.function.Consumer<ConnectionDiagnostics> callback) {
        ConnectionDiagnostics diagnostics = new ConnectionDiagnostics();
        diagnostics.endpoint = baseUrl;
        diagnostics.connected = hasConnection();
        if (baseUrl.isEmpty()) {
            post(callback, diagnostics);
            return;
        }
        executor.execute(() -> {
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
        });
    }

    public void listDirectory(String path, boolean refresh, ListCallback callback) {
        String normalized = Models.normalizeRemotePath(path);
        JSONObject payload = new JSONObject();
        try {
            payload.put("path", normalized);
            payload.put("password", "");
            payload.put("page", 1);
            payload.put("per_page", 0);
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
                    for (int i = 0; i < content.length(); i++) {
                        JSONObject object = content.optJSONObject(i);
                        if (object != null) {
                            entries.add(Models.RemoteEntry.fromJson(object, normalized));
                        }
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
        if (url == null) {
            post(callback, false, "文件下载地址无效。", new byte[0]);
            return;
        }
        executor.execute(() -> {
            HttpResult result = requestAbsolute(url, progressCallback);
            if (!result.error.isEmpty()) {
                post(callback, false, result.error, new byte[0]);
            } else if (result.status < 200 || result.status >= 300) {
                post(callback, false, "下载 HTTP 状态码 " + result.status, result.body);
            } else {
                post(callback, true, "success", result.body);
            }
        });
    }

    public void uploadFile(String remotePath, byte[] data, boolean overwrite, SimpleCallback callback) {
        String normalized = Models.normalizeRemotePath(remotePath);
        java.util.HashMap<String, String> headers = new java.util.HashMap<>();
        headers.put("File-Path", percentEncodePath(normalized));
        headers.put("As-Task", "false");
        headers.put("Overwrite", overwrite ? "true" : "false");
        executor.execute(() -> {
            HttpResult result = request("PUT", "/api/fs/put", data, headers);
            if (!result.error.isEmpty()) {
                post(callback, false, result.error);
                return;
            }
            ApiResponse api = parseApi(result.body);
            post(callback, result.status >= 200 && result.status < 300 && api.ok, api.message);
        });
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
        closed = true;
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
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
        executor.execute(() -> {
            HttpResult result = request("POST", endpoint,
                    payload.toString().getBytes(StandardCharsets.UTF_8), null);
            if (!result.error.isEmpty()) {
                post(callback, false, result.error, new JSONObject());
                return;
            }
            if (result.status < 200 || result.status >= 300) {
                post(callback, false, "HTTP 状态码 " + result.status, new JSONObject());
                return;
            }
            ApiResponse api = parseApi(result.body);
            post(callback, api.ok, api.message, api.data);
        });
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
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "OpenListSorter/1.0 Android");
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
            }
            if (!token.isEmpty() && sameOrigin(url, new URL(baseUrl))) {
                connection.setRequestProperty("Authorization", token);
            }
            if (extraHeaders != null) {
                for (java.util.Map.Entry<String, String> header : extraHeaders.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
                if (extraHeaders.containsKey("File-Path")) {
                    connection.setRequestProperty("Content-Type", "application/octet-stream");
                }
            }
            if (body != null) {
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
            }
            result.status = connection.getResponseCode();
            InputStream input = result.status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (input != null) {
                result.body = readAll(input, connection.getContentLengthLong(), progressCallback);
            }
        } catch (Exception error) {
            result.error = error.getMessage() == null ? error.toString() : error.getMessage();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return result;
    }

    private byte[] readAll(InputStream input, long total, ProgressCallback progressCallback) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            long received = 0;
            int count;
            while ((count = stream.read(buffer)) >= 0) {
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
