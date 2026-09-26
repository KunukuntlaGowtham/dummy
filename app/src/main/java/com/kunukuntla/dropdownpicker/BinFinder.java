package com.kunukuntla.dropdownpicker;

/**
 * Finds the dustbin on a card's number row in a screenshot. On the row, right of the number,
 * there are usually two icons: the bin and the expand arrow (a thin "v"). Both stand out from
 * the card's background; the bin is the one with the most ink (a filled or drawn can, against
 * the arrow's two thin strokes).
 */
final class BinFinder {

    private BinFinder() {
    }

    /**
     * Looks in the band {@code y0..y1}, {@code x0..x1} of {@code px} (ARGB, {@code w} wide) for
     * the icon with the most ink. Columns with ink closer than {@code gap} pixels count as one
     * icon. Returns {left, top, right, bottom} of the bin, or null if the row is empty.
     */
    static int[] find(int[] px, int w, int h, int y0, int y1, int x0, int x1, int gap) {
        y0 = Math.max(0, y0);
        y1 = Math.min(h, y1);
        x0 = Math.max(0, x0);
        x1 = Math.min(w, x1);
        if (y1 - y0 < 2 || x1 - x0 < 2) return null;

        int bg = background(px, w, y0, y1, x0, x1);
        int[] colInk = new int[x1 - x0];
        for (int y = y0; y < y1; y++) {
            int row = y * w;
            for (int x = x0; x < x1; x++) {
                if (isInk(px[row + x], bg)) colInk[x - x0]++;
            }
        }

        // Icons are small: an ink run as wide as a third of the screen is text or a line.
        int maxWidth = w / 3;
        int bestLeft = -1, bestRight = -1, bestInk = 0;
        int left = -1, right = -1, ink = 0, blank = 0;
        for (int i = 0; i <= colInk.length; i++) {
            boolean on = i < colInk.length && colInk[i] >= 2;
            if (on) {
                if (left < 0) left = i;
                right = i;
                ink += colInk[i];
                blank = 0;
            } else if (left >= 0 && (++blank > gap || i == colInk.length)) {
                int width = right - left + 1;
                if (width <= maxWidth && ink > bestInk) {
                    bestInk = ink;
                    bestLeft = left;
                    bestRight = right;
                }
                left = -1;
                ink = 0;
                blank = 0;
            }
        }
        if (bestLeft < 0) return null;

        // Top and bottom of the ink inside the chosen columns.
        int top = -1, bottom = -1;
        for (int y = y0; y < y1; y++) {
            int row = y * w;
            for (int x = x0 + bestLeft; x <= x0 + bestRight; x++) {
                if (isInk(px[row + x], bg)) {
                    if (top < 0) top = y;
                    bottom = y;
                    break;
                }
            }
        }
        if (top < 0) return null;
        return new int[] {x0 + bestLeft, top, x0 + bestRight + 1, bottom + 1};
    }

    /** The most common colour in the band (rounded to 16 levels a channel): the card itself. */
    private static int background(int[] px, int w, int y0, int y1, int x0, int x1) {
        int[] counts = new int[4096];
        for (int y = y0; y < y1; y++) {
            int row = y * w;
            for (int x = x0; x < x1; x++) counts[bucket(px[row + x])]++;
        }
        int best = 0;
        for (int i = 1; i < counts.length; i++) if (counts[i] > counts[best]) best = i;
        return ((best >> 8) & 15) * 17 << 16 | ((best >> 4) & 15) * 17 << 8 | (best & 15) * 17;
    }

    private static int bucket(int c) {
        return ((c >> 20) & 15) << 8 | ((c >> 12) & 15) << 4 | ((c >> 4) & 15);
    }

    private static boolean isInk(int c, int bg) {
        int d = Math.abs(((c >> 16) & 255) - ((bg >> 16) & 255))
                + Math.abs(((c >> 8) & 255) - ((bg >> 8) & 255))
                + Math.abs((c & 255) - (bg & 255));
        return d > 80;
    }
}
