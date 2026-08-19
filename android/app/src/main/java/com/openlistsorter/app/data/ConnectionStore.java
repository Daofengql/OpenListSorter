package com.openlistsorter.app.data;

import android.content.Context;
import android.content.SharedPreferences;

public final class ConnectionStore {
    private static final String PREFS = "connection";

    private ConnectionStore() {
    }

    public static ConnectionProfile load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ConnectionProfile profile = new ConnectionProfile();
        profile.endpoint = prefs.getString("endpoint", "");
        profile.token = prefs.getString("token", "");
        profile.username = prefs.getString("username", "");
        profile.password = prefs.getString("password", "");
        profile.authMode = "password".equals(prefs.getString("authMode", "token"))
                ? ConnectionProfile.AuthMode.PASSWORD : ConnectionProfile.AuthMode.TOKEN;
        profile.rememberMe = prefs.getBoolean("rememberMe", false);
        profile.autoLogin = prefs.getBoolean("autoLogin", false);
        if (!profile.rememberMe) {
            profile.autoLogin = false;
            profile.token = "";
            profile.username = "";
            profile.password = "";
        }
        if (profile.autoLogin && !profile.hasCredentials()) {
            profile.autoLogin = false;
        }
        return profile;
    }

    public static void save(Context context, ConnectionProfile profile) {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (!profile.rememberMe) {
            clearRememberedConnection(context);
            editor.putBoolean("rememberMe", false).putBoolean("autoLogin", false).apply();
            return;
        }
        editor.putString("endpoint", profile.endpoint == null ? "" : profile.endpoint.trim())
                .putString("authMode", profile.authMode == ConnectionProfile.AuthMode.PASSWORD ? "password" : "token")
                .putString("token", profile.token == null ? "" : profile.token.trim())
                .putString("username", profile.username == null ? "" : profile.username.trim())
                .putString("password", profile.password == null ? "" : profile.password)
                .putBoolean("rememberMe", true)
                .putBoolean("autoLogin", profile.autoLogin && profile.hasCredentials())
                .apply();
    }

    public static void disableAutoLogin(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("autoLogin", false).apply();
    }

    public static void clearRememberedConnection(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("endpoint").remove("authMode").remove("token")
                .remove("username").remove("password").remove("rememberMe")
                .remove("autoLogin").apply();
    }
}
