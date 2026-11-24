package com.kunukuntla.binpress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The numbers typed in the app. */
final class Numbers {

    private Numbers() {
    }

    /**
     * "3, 7 11" -> 11, 7, 3: whole numbers 1-99, each once, highest first. Highest first keeps every
     * number right while deleting: removing profile 11 never moves profile 7 or 3.
     */
    static List<Integer> parse(String text) {
        List<Integer> out = new ArrayList<>();
        if (text == null) return out;
        for (String part : text.split("[^0-9]+")) {
            if (part.isEmpty() || part.length() > 2) continue;
            int n = Integer.parseInt(part);
            if (n >= 1 && !out.contains(n)) out.add(n);
        }
        Collections.sort(out, Collections.reverseOrder());
        return out;
    }
}
