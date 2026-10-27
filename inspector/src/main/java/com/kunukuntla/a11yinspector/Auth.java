package com.kunukuntla.a11yinspector;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Sign-in with Supabase (email + password), through its Auth REST API - no SDK. The project's
 * URL and anon key come from the build (GitHub secrets SUPABASE_URL / SUPABASE_ANON_KEY).
 * <p>
 * The session (access + refresh token) is kept in this app's private storage. The app and its
 * round buttons work only while signed in; signing out - or the account being removed or
 * signed out on the server - locks them again.
 */
final class Auth {

    private static final String PREFS = "auth";
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private static Handler main;
    /** A renewal is on its way: no second one with the same (soon used) refresh token. */
    private static volatile boolean refreshing;

    /** The result of a call: ok, or the message to show. */
    static final class Result {
        final boolean ok;
        final String message;

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    private Auth() {
    }

    static boolean configured() {
        return !BuildConfig.SUPABASE_URL.isEmpty() && !BuildConfig.SUPABASE_ANON_KEY.isEmpty();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** A session is kept (signed in and not signed out since). */
    static boolean signedIn(Context c) {
        return configured() && !prefs(c).getString("refresh_token", "").isEmpty();
    }

    static String email(Context c) {
        return prefs(c).getString("email", "");
    }

    static void signIn(Context c, String email, String password, Consumer<Result> done) {
        JSONObject body = new JSONObject();
        try {
            body.put("email", email.trim());
            body.put("password", password);
        } catch (JSONException e) {
            done.accept(new Result(false, "Couldn't send that"));
            return;
        }
        call(c, "/auth/v1/token?grant_type=password", body, null, (code, json) -> {
            if (code == 200 && keep(c, json)) return new Result(true, "Signed in");
            return new Result(false, message(code, json, "Wrong email or password"));
        }, done);
    }

    static void signUp(Context c, String email, String password, Consumer<Result> done) {
        JSONObject body = new JSONObject();
        try {
            body.put("email", email.trim());
            body.put("password", password);
        } catch (JSONException e) {
            done.accept(new Result(false, "Couldn't send that"));
            return;
        }
        call(c, "/auth/v1/signup", body, null, (code, json) -> {
            if (code == 200 || code == 201) {
                // With "Confirm email" on, there is no session yet: the link in the email first.
                if (json != null && json.has("access_token") && keep(c, json)) return new Result(true, "Account made - signed in");
                return new Result(false, "Account made - open the link in the email we sent, then sign in");
            }
            return new Result(false, message(code, json, "Couldn't make the account"));
        }, done);
    }

    static void resetPassword(Context c, String email, Consumer<Result> done) {
        JSONObject body = new JSONObject();
        try {
            body.put("email", email.trim());
        } catch (JSONException e) {
            done.accept(new Result(false, "Couldn't send that"));
            return;
        }
        call(c, "/auth/v1/recover", body, null, (code, json) -> code == 200
                ? new Result(true, "A reset link was sent to " + email.trim())
                : new Result(false, message(code, json, "Couldn't send the reset link")), done);
    }

    /** Signs out here at once; the server is told too (its session ends there). */
    static void signOut(Context c) {
        String token = prefs(c).getString("access_token", "");
        prefs(c).edit().clear().apply();
        if (!token.isEmpty() && configured()) {
            call(c, "/auth/v1/logout", new JSONObject(), token, (code, json) -> new Result(true, ""), r -> { });
        }
    }

    /**
     * The access token renewed when it is (nearly) out of date. Signed out here when the server
     * refuses the session (signed out elsewhere, or the account removed); kept when offline.
     */
    static void refreshIfNeeded(Context c) {
        if (!signedIn(c) || refreshing) return;
        long expires = prefs(c).getLong("expires_at", 0);
        if (System.currentTimeMillis() / 1000 < expires - 120) return;
        String used = prefs(c).getString("refresh_token", "");
        JSONObject body = new JSONObject();
        try {
            body.put("refresh_token", used);
        } catch (JSONException e) {
            return;
        }
        refreshing = true;
        call(c, "/auth/v1/token?grant_type=refresh_token", body, null, (code, json) -> {
            if (code == 200 && keep(c, json)) return new Result(true, "");
            // Refused: signed out - only when the session is still the one sent (a sign-in
            // meanwhile is never undone), and never for a network or server fault (kept).
            if ((code == 400 || code == 401 || code == 403) && used.equals(prefs(c).getString("refresh_token", ""))) {
                prefs(c).edit().clear().apply();
            }
            return new Result(false, "");
        }, r -> refreshing = false);
    }

    // ---- the calls ----------------------------------------------------------------------

    private interface Reply {
        Result read(int code, JSONObject json);
    }

    private static void call(Context c, String path, JSONObject body, String bearer, Reply reply, Consumer<Result> done) {
        if (main == null) main = new Handler(Looper.getMainLooper());
        if (!configured()) {
            done.accept(new Result(false, "Supabase isn't set up in this build"));
            return;
        }
        NET.execute(() -> {
            Result r;
            HttpURLConnection http = null;
            try {
                http = (HttpURLConnection) new URL(BuildConfig.SUPABASE_URL.replaceAll("/+$", "") + path).openConnection();
                http.setRequestMethod("POST");
                http.setConnectTimeout(10000);
                http.setReadTimeout(15000);
                http.setDoOutput(true);
                http.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY);
                http.setRequestProperty("Authorization", "Bearer " + (bearer != null ? bearer : BuildConfig.SUPABASE_ANON_KEY));
                http.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = http.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = http.getResponseCode();
                InputStream in = code >= 400 ? http.getErrorStream() : http.getInputStream();
                JSONObject json = null;
                if (in != null) {
                    String text = read(in);
                    if (!text.trim().isEmpty()) {
                        try {
                            json = new JSONObject(text);
                        } catch (JSONException ignored) {
                        }
                    }
                }
                r = reply.read(code, json);
            } catch (java.io.IOException e) {
                r = new Result(false, "No connection - check the internet and try again");
            } finally {
                if (http != null) http.disconnect();
            }
            Result result = r;
            main.post(() -> done.accept(result));
        });
    }

    private static String read(InputStream in) throws java.io.IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = s.read(b)) > 0) buf.write(b, 0, n);
            return buf.toString(StandardCharsets.UTF_8.name());
        }
    }

    /** A session in the reply, kept: true when there was one. */
    private static boolean keep(Context c, JSONObject json) {
        if (json == null) return false;
        String access = json.optString("access_token", ""), refresh = json.optString("refresh_token", "");
        if (access.isEmpty() || refresh.isEmpty()) return false;
        long expiresIn = json.optLong("expires_in", 3600);
        String email = "";
        JSONObject user = json.optJSONObject("user");
        if (user != null) email = user.optString("email", "");
        prefs(c).edit()
                .putString("access_token", access)
                .putString("refresh_token", refresh)
                .putLong("expires_at", System.currentTimeMillis() / 1000 + expiresIn)
                .putString("email", email.isEmpty() ? prefs(c).getString("email", "") : email)
                .apply();
        return true;
    }

    /** The server's own words for what went wrong, else {@code fallback}. */
    private static String message(int code, JSONObject json, String fallback) {
        if (code == 429) return "Too many tries - wait a minute";
        if (json == null) return fallback;
        for (String k : new String[] {"error_description", "msg", "message"}) {
            String m = json.optString(k, "");
            if (!m.isEmpty()) return m;
        }
        return fallback;
    }
}
