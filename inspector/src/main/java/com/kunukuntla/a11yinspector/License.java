package com.kunukuntla.a11yinspector;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.function.Consumer;

/**
 * The licence, kept on your Supabase server: after sign-in the app asks the database function
 * check_license() - answered for THIS login only - whether the login may use the app on this
 * phone. The server (licenses.sql) decides: the email is on the list, active and not ended; the
 * phone is one of its allowed phones (2 by default - a third is refused); the kill switch is off.
 * The app keeps working for 24 h after its last "yes" (so a short loss of internet is fine) and
 * is locked after that, or at once when the next check says no.
 * <p>
 * Until licenses.sql has been run (the function doesn't exist yet) the check is skipped, so
 * nobody is locked out while you set it up.
 */
final class License {

    private static final String PREFS = "license";
    /** A "yes" holds for this long without another check. */
    private static final long VALID_MS = 24L * 60 * 60 * 1000;

    private License() {
    }

    /** Always on (the server decides; with no function there yet, the answer is "not set up" = yes). */
    static boolean enforced() {
        return Auth.configured();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Why the server said no last time (shown on the sign-in screen), or "". */
    static String lastError(Context c) {
        return prefs(c).getString("error", "");
    }

    /** Signed out: the answer is dropped. */
    static void forget(Context c) {
        prefs(c).edit().clear().apply();
    }

    /** The last answer was yes, less than 24 h ago, and the clock wasn't set back. */
    static boolean ok(Context c) {
        if (!enforced()) return true;
        SharedPreferences sp = prefs(c);
        long now = System.currentTimeMillis();
        long yes = sp.getLong("yes_at", 0), seen = sp.getLong("seen", 0);
        if (yes == 0 || now + 300_000 < seen || now + 300_000 < yes) return false;
        if (now > seen + 60_000) sp.edit().putLong("seen", now).apply();
        return now - yes < VALID_MS;
    }

    /** Checked on every account check (every 15 min): one small call. */
    static boolean dueForRenewal(Context c) {
        return enforced();
    }

    /** Asks the server now; {@code done}: true when the answer is yes (or the server has no licence set up). */
    static void renew(Context c, Consumer<Boolean> done) {
        String access = Auth.accessToken(c);
        if (!enforced() || access.isEmpty()) {
            done.accept(false);
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("p_device", Auth.deviceId(c));
            body.put("p_model", (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).trim());
        } catch (JSONException e) {
            done.accept(false);
            return;
        }
        Auth.post(c, "/rest/v1/rpc/check_license", body, access, (code, json) -> {
            SharedPreferences.Editor e = prefs(c).edit();
            long now = System.currentTimeMillis();
            if (code == 404) {
                // No check_license() on the server yet: licences aren't set up - the sign-in alone applies.
                e.putLong("yes_at", now).putLong("seen", now).remove("error").apply();
                done.accept(true);
            } else if (code == 200 && json != null && json.optBoolean("ok", false)) {
                saveOrders(c, json);
                e.putLong("yes_at", now).putLong("seen", now).remove("error").apply();
                done.accept(true);
            } else if (code == 200 && json != null) {
                // A real "no" (revoked, no licence, too many phones, switched off): locked at once.
                e.remove("yes_at").putString("error", json.optString("message", "No licence for this login")).apply();
                done.accept(false);
            } else {
                // No connection / server fault / expired session: nothing changes (the 24 h still run).
                done.accept(ok(c));
            }
        });
    }

    // ---- admin orders: the server says this phone is stopped, or which buttons are off ----

    static void saveOrders(Context c, JSONObject json) {
        SharedPreferences.Editor e = prefs(c).edit();
        e.putBoolean("stopped", json.optBoolean("stopped", false));
        e.putString("adminMsg", json.optString("msg", ""));
        org.json.JSONArray off = json.optJSONArray("off");
        StringBuilder keys = new StringBuilder(",");
        if (off != null) for (int i = 0; i < off.length(); i++) keys.append(off.optString(i)).append(',');
        e.putString("off", keys.toString());
        e.putLong("orders_at", System.currentTimeMillis());
        e.apply();
    }

    /** The admin stopped this phone (every button does nothing, the message is shown). */
    static boolean stopped(Context c) {
        return enforced() && prefs(c).getBoolean("stopped", false);
    }

    /** This button was turned off by the admin ("tick", "fast", "auto", "delete" ...). */
    static boolean buttonOff(Context c, String key) {
        if (!enforced()) return false;
        return prefs(c).getString("off", ",").contains("," + key + ",");
    }

    static String adminMessage(Context c) {
        String m = prefs(c).getString("adminMsg", "");
        return "⛔ " + (m.isEmpty() ? "Stopped by the admin" : m);
    }

    /** The buttons turned off, as ",tick,auto," - to see whether anything changed. */
    static String offKeys(Context c) {
        return enforced() ? prefs(c).getString("off", ",") : ",";
    }

    /**
     * The fast poll (every ~30 s while the service is on): just the orders, one small call.
     * {@code after} runs after every answer (the service compares with what it shows).
     */
    static void poll(Context c, Runnable after) {
        String access = Auth.accessToken(c);
        if (!enforced() || access.isEmpty()) return;
        JSONObject body = new JSONObject();
        try {
            body.put("p_device", Auth.deviceId(c));
        } catch (JSONException e) {
            return;
        }
        Auth.post(c, "/rest/v1/rpc/device_commands", body, access, (code, json) -> {
            if (code == 200 && json != null) {
                saveOrders(c, json);
                if (after != null) after.run();
            }
        });
    }
}
