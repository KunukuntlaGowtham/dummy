package com.kunukuntla.dropdownpicker;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds a dropdown in a screenshot by its shape: a long, thin horizontal line
 * (the field's underline) with a small down arrow (a chevron or a filled
 * triangle) just above its right end.
 */
final class DropdownDetector {

    static final class Hit {
        /** Where to tap to open the dropdown (the arrow's centre). */
        final int tapX, tapY;
        /** The underline. */
        final Rect line;
        /** The down arrow. */
        final Rect arrow;

        Hit(Rect line, Rect arrow) {
            this.tapX = arrow.centerX();
            this.tapY = arrow.centerY();
            this.line = line;
            this.arrow = arrow;
        }
    }

    /** Everything the detector saw, for showing it to the user. */
    static final class Result {
        /** All long thin lines found, whether or not they had an arrow. */
        final List<Rect> lines;
        /** Every dropdown found, top to bottom. */
        final List<Hit> hits;
        /** The top-most dropdown, or null. */
        final Hit hit;

        Result(List<Rect> lines, List<Hit> hits) {
            this.lines = lines;
            this.hits = hits;
            this.hit = hits.isEmpty() ? null : hits.get(0);
        }
    }

    private static final int DARK_LUMA = 150;

    private DropdownDetector() {}

    /** Finds every dropdown on screen, top to bottom. */
    static Result analyze(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        boolean[] dark = new boolean[w * h];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int luma = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
            dark[i] = luma < DARK_LUMA;
        }

        List<Rect> lines = findLines(dark, w, h);
        List<Hit> hits = new ArrayList<>();
        for (Rect line : lines) {
            Rect arrow = findArrow(dark, w, line);
            if (arrow != null) hits.add(new Hit(line, arrow));
        }
        return new Result(lines, hits);
    }

    /** Long, thin horizontal dark lines, top to bottom. */
    private static List<Rect> findLines(boolean[] dark, int w, int h) {
        int minRun = w * 35 / 100;
        int maxThickness = Math.max(4, w / 100);
        List<Rect> lines = new ArrayList<>();
        Rect current = null;

        for (int y = 0; y < h; y++) {
            int bestStart = -1, bestLen = 0, runStart = -1;
            int row = y * w;
            for (int x = 0; x <= w; x++) {
                boolean d = x < w && dark[row + x];
                if (d && runStart < 0) {
                    runStart = x;
                } else if (!d && runStart >= 0) {
                    if (x - runStart > bestLen) {
                        bestLen = x - runStart;
                        bestStart = runStart;
                    }
                    runStart = -1;
                }
            }

            if (bestLen >= minRun) {
                int x0 = bestStart, x1 = bestStart + bestLen - 1;
                if (current != null && current.bottom == y - 1
                        && x0 <= current.right && x1 >= current.left) {
                    current.bottom = y;
                    current.left = Math.min(current.left, x0);
                    current.right = Math.max(current.right, x1);
                } else {
                    if (current != null) lines.add(current);
                    current = new Rect(x0, y, x1, y);
                }
            }
        }
        if (current != null) lines.add(current);

        // Keep only thin lines with mostly light pixels just above and below
        // (edges of big dark blocks are not underlines).
        List<Rect> thin = new ArrayList<>();
        for (Rect r : lines) {
            int thickness = r.bottom - r.top + 1;
            if (thickness > maxThickness) continue;
            int above = r.top - 3, below = r.bottom + 3;
            if (above < 0 || below >= h) continue;
            if (darkFraction(dark, w, above, r.left, r.right) > 0.3f) continue;
            if (darkFraction(dark, w, below, r.left, r.right) > 0.3f) continue;
            thin.add(r);
        }
        return thin;
    }

    private static float darkFraction(boolean[] dark, int w, int y, int x0, int x1) {
        int n = 0;
        for (int x = x0; x <= x1; x++) if (dark[y * w + x]) n++;
        return n / (float) (x1 - x0 + 1);
    }

    /** Looks for a down arrow just above the right end of the line. */
    private static Rect findArrow(boolean[] dark, int w, Rect line) {
        int rx0 = Math.max(0, line.right - w * 12 / 100);
        int rx1 = Math.min(w - 1, line.right + w * 2 / 100);
        int ry0 = Math.max(0, line.top - w * 14 / 100);
        int ry1 = line.top - 2;
        if (ry1 <= ry0) return null;

        int rw = rx1 - rx0 + 1, rh = ry1 - ry0 + 1;
        boolean[] seen = new boolean[rw * rh];
        int[] queue = new int[rw * rh];
        Rect best = null;

        for (int sy = 0; sy < rh; sy++) {
            for (int sx = 0; sx < rw; sx++) {
                int si = sy * rw + sx;
                if (seen[si] || !dark[(ry0 + sy) * w + rx0 + sx]) continue;

                // Flood-fill one connected blob (8-neighbour).
                int head = 0, tail = 0;
                queue[tail++] = si;
                seen[si] = true;
                int minX = sx, maxX = sx, minY = sy, maxY = sy;
                while (head < tail) {
                    int p = queue[head++];
                    int px = p % rw, py = p / rw;
                    minX = Math.min(minX, px);
                    maxX = Math.max(maxX, px);
                    minY = Math.min(minY, py);
                    maxY = Math.max(maxY, py);
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = px + dx, ny = py + dy;
                            if (nx < 0 || ny < 0 || nx >= rw || ny >= rh) continue;
                            int ni = ny * rw + nx;
                            if (seen[ni] || !dark[(ry0 + ny) * w + rx0 + nx]) continue;
                            seen[ni] = true;
                            queue[tail++] = ni;
                        }
                    }
                }

                // The blob's pixels are queue[0..tail).
                int bw = maxX - minX + 1, bh = maxY - minY + 1;
                if (bw < w / 80 || bw > w / 10 || bh < 3 || bw < bh * 1.2f) continue;
                if (!isDownArrow(queue, tail, rw, minX, maxX, minY, maxY)) continue;

                Rect r = new Rect(rx0 + minX, ry0 + minY, rx0 + maxX, ry0 + maxY);
                if (best == null || r.right > best.right) best = r;
            }
        }
        return best;
    }

    /** Wide at the top, narrowing to a point in the middle at the bottom. */
    private static boolean isDownArrow(int[] pixels, int count, int rw,
                                       int minX, int maxX, int minY, int maxY) {
        int bw = maxX - minX + 1, bh = maxY - minY + 1;
        int band = Math.max(1, bh / 4);
        int topMin = Integer.MAX_VALUE, topMax = Integer.MIN_VALUE;
        int botMin = Integer.MAX_VALUE, botMax = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            int x = pixels[i] % rw, y = pixels[i] / rw;
            if (y < minY + band) {
                topMin = Math.min(topMin, x);
                topMax = Math.max(topMax, x);
            }
            if (y > maxY - band) {
                botMin = Math.min(botMin, x);
                botMax = Math.max(botMax, x);
            }
        }
        if (botMin == Integer.MAX_VALUE) return false;
        int topSpan = topMax - topMin + 1;
        int botSpan = botMax - botMin + 1;
        float centre = (minX + maxX) / 2f;
        float botCentre = (botMin + botMax) / 2f;
        return topSpan >= bw * 0.7f
                && botSpan <= bw * 0.5f
                && Math.abs(botCentre - centre) <= bw * 0.2f;
    }
}
