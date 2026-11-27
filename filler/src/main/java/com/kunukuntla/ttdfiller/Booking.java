package com.kunukuntla.ttdfiller;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One row of ttd_db: a booking of up to eight people - the lead (sevakname, age, gender, idtype,
 * idnumber) and p2..p8 - plus the contact details. The rows are kept on the phone so the
 * picker opens at once on the TTD page; ⟳ reads them again.
 */
final class Booking {

    static final int MAX_PARTY = 8;
    /** The contact boxes a page may also have, filled when it has them (country before state before district). */
    static final String[] CONTACTS = {"country", "state", "district", "city", "pincode", "mobile", "email", "gothram"};

    static final class Person {
        final String name, age, gender, idType, idNumber;

        Person(String name, String age, String gender, String idType, String idNumber) {
            this.name = name;
            this.age = age;
            this.gender = gender;
            this.idType = idType;
            this.idNumber = idNumber;
        }
    }

    final JSONObject row;

    Booking(JSONObject row) {
        this.row = row;
    }

    long id() {
        return row.optLong("id", -1);
    }

    String code() {
        return s("code");
    }

    String lead() {
        return s("sevakname");
    }

    String s(String key) {
        if (!row.has(key) || row.isNull(key)) return "";
        return row.optString(key, "").trim();
    }

    /** Everyone on the booking in order: the lead, then each pN that has a name. */
    List<Person> people() {
        List<Person> out = new ArrayList<>();
        if (!lead().isEmpty()) {
            out.add(new Person(lead(), s("age"), s("gender"), s("idtype"), s("idnumber")));
        }
        for (int n = 2; n <= MAX_PARTY; n++) {
            String p = "p" + n + "_";
            String name = s(p + "name");
            if (name.isEmpty()) continue;
            out.add(new Person(name, s(p + "age"), s(p + "gender"), s(p + "idtype"), s(p + "idnumber")));
        }
        return out;
    }

    String title() {
        int n = people().size();
        String c = code().isEmpty() ? "NO CODE" : code();
        return c + " · " + (lead().isEmpty() ? "—" : lead()) + " · " + n + (n == 1 ? " person" : " people");
    }

    boolean matches(String query) {
        String q = query.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) return true;
        StringBuilder all = new StringBuilder(code()).append(' ').append(s("mobile")).append(' ').append(s("idnumber"));
        for (Person p : people()) all.append(' ').append(p.name);
        return all.toString().toLowerCase(Locale.ROOT).contains(q);
    }

    /** "••••9012": enough to tell IDs apart on screen without showing them whole. */
    static String masked(String id) {
        String t = id.replaceAll("\\s", "");
        if (t.isEmpty()) return "—";
        return t.length() <= 4 ? t : "••••" + t.substring(t.length() - 4);
    }

    // ---- kept on the phone ----

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("fill", Context.MODE_PRIVATE);
    }

    static List<Booking> cached(Context c) {
        List<Booking> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("rows", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) out.add(new Booking(o));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static Booking picked(Context c) {
        long id = prefs(c).getLong("picked_id", -1);
        if (id < 0) return null;
        for (Booking b : cached(c)) if (b.id() == id) return b;
        return null;
    }

    static void pick(Context c, long id) {
        prefs(c).edit().putLong("picked_id", id).apply();
    }

    static long refreshedAt(Context c) {
        return prefs(c).getLong("rows_at", 0);
    }

    /** Reads every booking from ttd_db again (waits for the network). "" when done, else what went wrong. */
    static String refresh(Context c) {
        try {
            Supa.Reply r = Supa.get(c, "ttd_db?select=*&order=code.asc");
            if (!r.ok()) {
                if (r.code == 401) return "Sign in again (your sign-in ended)";
                if (r.code == 404) return "No ttd_db table on the server";
                return "Couldn't read ttd_db (" + r.code + ") " + Supa.authError(r.body);
            }
            JSONArray a = new JSONArray(r.body);
            prefs(c).edit().putString("rows", a.toString()).putLong("rows_at", System.currentTimeMillis()).apply();
            return "";
        } catch (Exception e) {
            return "No internet? " + Supa.message(e);
        }
    }
}
