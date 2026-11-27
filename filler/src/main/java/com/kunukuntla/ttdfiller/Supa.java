package com.kunukuntla.ttdfiller;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Supabase over plain HTTPS: your sign-in (kept, and renewed by itself - a sign-in lasts an hour)
 * and reading a table with it. Every call waits for the network: run them off the main thread.
 */
final class Supa {

    private Supa() {
    }

    static final class Reply {
        final int code;
        final String body;

        Reply(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }

        boolean ok() {
            return code >= 200 && code < 300;
        }
    }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("session", Context.MODE_PRIVATE);
    }

    static boolean signedIn(Context c) {
        return !prefs(c).getString("refresh", "").isEmpty();
    }

    static String email(Context c) {
        return prefs(c).getString("email", "");
    }

    /** "" when signed in, else what went wrong. */
    static String signIn(Context c, String email, String password) {
        try {
            Reply r = send("POST", "/auth/v1/token?grant_type=password", null,
                    new JSONObject().put("email", email).put("password", password).toString());
            if (r.code == 200 && keep(c, new JSONObject(r.body))) {
                prefs(c).edit().putString("email", email).apply();
                return "";
            }
            return "(" + r.code + ") " + authError(r.body);
        } catch (Exception e) {
            return message(e);
        }
    }

    /** A new access token from the kept refresh token. */
    static synchronized boolean renew(Context c) {
        String refresh = prefs(c).getString("refresh", "");
        if (refresh.isEmpty()) return false;
        try {
            Reply r = send("POST", "/auth/v1/token?grant_type=refresh_token", null,
                    new JSONObject().put("refresh_token", refresh).toString());
            return r.code == 200 && keep(c, new JSONObject(r.body));
        } catch (Exception e) {
            return false;
        }
    }

    static void signOut(Context c) {
        prefs(c).edit().remove("access").remove("refresh").remove("expires").apply();
    }

    /** GET /rest/v1/{path} as you (renewed first when it is about to end, and once more on a 401). */
    static Reply get(Context c, String path) throws Exception {
        if (!signedIn(c)) return new Reply(401, "not signed in");
        SharedPreferences sp = prefs(c);
        if (System.currentTimeMillis() > sp.getLong("expires", 0) - 120_000) renew(c);
        Reply r = send("GET", "/rest/v1/" + path, sp.getString("access", ""), null);
        if (r.code == 401 && renew(c)) r = send("GET", "/rest/v1/" + path, sp.getString("access", ""), null);
        return r;
    }

    private static boolean keep(Context c, JSONObject j) {
        String a = j.optString("access_token", "");
        if (a.isEmpty()) return false;
        SharedPreferences sp = prefs(c);
        sp.edit().putString("access", a)
                .putString("refresh", j.optString("refresh_token", sp.getString("refresh", "")))
                .putLong("expires", System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000)
                .apply();
        return true;
    }

    private static Reply send(String method, String path, String token, String body) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(BuildConfig.SUPABASE_URL + path).openConnection();
        try {
            h.setConnectTimeout(15_000);
            h.setReadTimeout(20_000);
            h.setRequestMethod(method);
            h.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY);
            h.setRequestProperty("Authorization", "Bearer "
                    + (token == null || token.isEmpty() ? BuildConfig.SUPABASE_ANON_KEY : token));
            h.setRequestProperty("Accept", "application/json");
            if (body != null) {
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = h.getOutputStream()) {
                    o.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = h.getResponseCode();
            InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
            return new Reply(code, in == null ? "" : readAll(in));
        } finally {
            h.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    static String authError(String s) {
        try {
            JSONObject j = new JSONObject(s);
            String m = j.optString("error_description", j.optString("msg", j.optString("message", j.optString("error", ""))));
            if (!m.isEmpty()) return m;
        } catch (Exception ignored) {
        }
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
