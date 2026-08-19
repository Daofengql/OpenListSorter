package com.openlistsorter.app.data;

public final class ConnectionProfile {
    public enum AuthMode { TOKEN, PASSWORD }

    public String endpoint = "";
    public String token = "";
    public String username = "";
    public String password = "";
    public AuthMode authMode = AuthMode.TOKEN;
    public boolean rememberMe;
    public boolean autoLogin;

    public boolean hasCredentials() {
        if (endpoint == null || endpoint.trim().isEmpty()) {
            return false;
        }
        if (authMode == AuthMode.TOKEN) {
            return token != null && !token.trim().isEmpty();
        }
        return username != null && !username.trim().isEmpty()
                && password != null && !password.isEmpty();
    }
}
