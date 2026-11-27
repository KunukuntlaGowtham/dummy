package com.kunukuntla.ttdfiller;

import java.util.List;
import java.util.Locale;

/**
 * Matching the booking's words to what the page offers: "Male" is never taken for "Female", and
 * each ID type has the other spellings the page may use ("Aadhaar Card", "Aadhar", "Driving Licence" ...).
 */
final class Match {

    private Match() {
    }

    /** Lower case, words only. */
    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replace("…", "").replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** Lower case, letters and digits only, no spaces. */
    static String squash(String s) {
        return norm(s).replace(" ", "");
    }

    static boolean useful(String s) {
        return s != null && s.trim().length() >= 2 && s.matches("(?s).*[A-Za-z].*");
    }

    /** 0 no; 2 every word of {@code want} is a word of {@code label}; 3 the same. */
    static int score(String label, String want) {
        String l = norm(label), w = norm(want);
        if (l.isEmpty() || w.isEmpty()) return 0;
        if (l.equals(w) || l.replace(" ", "").equals(w.replace(" ", ""))) return 3;
        String[] words = l.split(" ");
        for (String word : w.split(" ")) {
            boolean found = false;
            for (String t : words) {
                if (t.equals(word) || word.length() >= 4 && t.startsWith(word)) {
                    found = true;
                    break;
                }
            }
            if (!found) return 0;
        }
        return 2;
    }

    /** The option for the first of {@code wants} that any option fits (the closest, then the shortest); -1 none. */
    static int best(List<String> options, String[] wants) {
        for (String w : wants) {
            int best = -1, bestScore = 0;
            for (int i = 0; i < options.size(); i++) {
                int sc = score(options.get(i), w);
                if (sc == 0) continue;
                if (sc > bestScore || sc == bestScore && norm(options.get(i)).length() < norm(options.get(best)).length()) {
                    best = i;
                    bestScore = sc;
                }
            }
            if (best >= 0) return best;
        }
        return -1;
    }

    /** The box already shows one of {@code wants}. */
    static boolean fits(String value, String[] wants) {
        for (String w : wants) if (score(value, w) >= 2) return true;
        return false;
    }

    /** Typed text the same (case and spaces aside: "1234 5678 9012" is "123456789012"). */
    static boolean same(String a, String b) {
        return a.replaceAll("\\s+", "").equalsIgnoreCase(b.replaceAll("\\s+", ""));
    }

    // ---- the booking's values, as the page wants them ----

    static String[] genders(String g) {
        String n = squash(g);
        if (n.startsWith("f") || n.equals("woman") || n.equals("w")) return new String[]{"Female"};
        if (n.startsWith("m") || n.equals("man")) return new String[]{"Male"};
        if (n.startsWith("t") || n.startsWith("o") || n.contains("third")) {
            return new String[]{"Transgender", "Third Gender", "Other", "Others"};
        }
        return new String[]{g};
    }

    static String[] proofs(String t) {
        String n = squash(t);
        if (n.isEmpty() || n.contains("aadh") || n.contains("adha") || n.contains("adhar") || n.contains("uid")) {
            return new String[]{"Aadhaar Card", "Aadhaar", "Aadhar Card", "Aadhar", "Adhaar", "UID"};
        }
        if (n.startsWith("pan")) return new String[]{"PAN Card", "PAN"};
        if (n.contains("voter") || n.contains("epic") || n.contains("election")) {
            return new String[]{"Voter ID", "Voter Id Card", "Voter", "Election"};
        }
        if (n.contains("driv") || n.contains("licen") || n.equals("dl")) {
            return new String[]{"Driving License", "Driving Licence", "Driving"};
        }
        if (n.contains("passport")) return new String[]{"Passport"};
        return new String[]{t};
    }

    /** The ID type as written in the booking ("Aadhaar Card" when it has none). */
    static String proofName(String t) {
        return t == null || t.trim().isEmpty() ? "Aadhaar Card" : t.trim();
    }

    /** The age as digits ("38", also from "38 yrs" or 38.0). */
    static String age(String a) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{1,3}").matcher(a == null ? "" : a);
        return m.find() ? m.group() : "";
    }

    static String name(String n) {
        return n == null ? "" : n.replaceAll("\\s+", " ").trim();
    }

    /** The ID number without spaces or dashes, letters in capitals (PAN, passport). */
    static String idNumber(String id) {
        return id == null ? "" : id.replaceAll("[\\s\\-]", "").toUpperCase(Locale.ROOT);
    }

    static String contact(String key, String v) {
        if (v == null) return "";
        v = v.trim();
        if (key.equals("mobile")) {
            String d = v.replaceAll("\\D", "");
            return d.length() > 10 ? d.substring(d.length() - 10) : d;
        }
        if (key.equals("pincode")) return v.replaceAll("\\D", "");
        return v.replaceAll("\\s+", " ");
    }
}
