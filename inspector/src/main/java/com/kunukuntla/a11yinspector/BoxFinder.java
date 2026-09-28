package com.kunukuntla.a11yinspector;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Finds empty checkboxes in a screenshot (the same way as the main app's Tick): an empty box
 * is a small, roughly square outline with nothing inside it. A ticked box is filled in, so it
 * fails the "nothing inside" test and is not found. Works on a grey-level copy of the screen.
 */
final class BoxFinder {

    private static final int EDGE = 26;      // grey levels between a line and its background
    private static final int MAX_BOXES = 60;

    private BoxFinder() {
    }

    static List<Rect> find(int[] lum, int w, int h, int minPx, int maxPx) {
        List<Rect> found = new ArrayList<>();
        if (w < 4 || h < 4 || minPx < 2) return found;
        int size = w * h;

        // 1. outline pixels: where the picture changes sharply
        boolean[] edge = new boolean[size];
        for (int y = 0; y < h - 1; y++) {
            int row = y * w;
            for (int x = 0; x < w - 1; x++) {
                int k = row + x;
                int v = lum[k];
                if (Math.abs(v - lum[k + 1]) > EDGE || Math.abs(v - lum[k + w]) > EDGE) edge[k] = true;
            }
        }

        // 2. join them into shapes (also corner to corner) and keep the empty-box-shaped ones
        boolean[] seen = new boolean[size];
        int[] stack = new int[size];
        int[] members = new int[size];
        for (int start = 0; start < size; start++) {
            if (!edge[start] || seen[start]) continue;
            int sp = 0;
            stack[sp++] = start;
            seen[start] = true;
            int count = 0, minX = w, maxX = 0, minY = h, maxY = 0;
            while (sp > 0) {
                int idx = stack[--sp];
                int x = idx % w, y = idx / w;
                members[count++] = idx;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        int n = ny * w + nx;
                        if (edge[n] && !seen[n]) {
                            seen[n] = true;
                            stack[sp++] = n;
                        }
                    }
                }
            }
            int bw = maxX - minX + 1, bh = maxY - minY + 1;
            if (bw < minPx || bh < minPx || bw > maxPx || bh > maxPx) continue;
            float aspect = bw / (float) bh;
            if (aspect < 0.75f || aspect > 1.33f) continue;
            if (count / (float) (bw * bh) > 0.55f) continue;          // solid shape, not an outline
            if (!ringLike(members, count, w, minX, minY, bw, bh)) continue;
            if (!hollow(lum, edge, w, minX, minY, bw, bh)) continue;
            found.add(new Rect(minX, minY, maxX + 1, maxY + 1));
            if (found.size() >= MAX_BOXES * 2) break;
        }
        return dedupe(found);
    }

    /** The shape runs along all four sides of its own bounding box. */
    private static boolean ringLike(int[] members, int count, int w, int minX, int minY, int bw, int bh) {
        boolean[] top = new boolean[bw], bottom = new boolean[bw], left = new boolean[bh], right = new boolean[bh];
        int tol = Math.max(2, Math.min(bw, bh) / 6);
        for (int i = 0; i < count; i++) {
            int idx = members[i];
            int x = idx % w - minX, y = idx / w - minY;
            if (x < 0 || y < 0 || x >= bw || y >= bh) continue;
            if (y <= tol) top[x] = true;
            if (y >= bh - 1 - tol) bottom[x] = true;
            if (x <= tol) left[y] = true;
            if (x >= bw - 1 - tol) right[y] = true;
        }
        return covered(top) && covered(bottom) && covered(left) && covered(right);
    }

    private static boolean covered(boolean[] side) {
        int n = 0;
        for (boolean f : side) if (f) n++;
        return n / (float) side.length > 0.5f;
    }

    /** The middle of the box is empty and flat: no tick, no label, no picture. */
    private static boolean hollow(int[] lum, boolean[] edge, int w, int minX, int minY, int bw, int bh) {
        int insetX = Math.max(2, bw / 4), insetY = Math.max(2, bh / 4);
        int x0 = minX + insetX, x1 = minX + bw - insetX, y0 = minY + insetY, y1 = minY + bh - insetY;
        if (x1 <= x0 || y1 <= y0) return false;
        int edges = 0, total = 0, lo = 255, hi = 0;
        for (int y = y0; y < y1; y++) {
            int row = y * w;
            for (int x = x0; x < x1; x++) {
                int k = row + x;
                total++;
                if (edge[k]) edges++;
                int v = lum[k];
                if (v < lo) lo = v;
                if (v > hi) hi = v;
            }
        }
        return total > 0 && edges * 100 / total < 12 && hi - lo < 48;
    }

    private static List<Rect> dedupe(List<Rect> list) {
        List<Rect> sorted = new ArrayList<>(list);
        Collections.sort(sorted, (a, b) -> Integer.compare(a.top, b.top));
        List<Rect> out = new ArrayList<>();
        for (Rect r : sorted) {
            boolean overlaps = false;
            for (Rect o : out) if (Rect.intersects(o, r)) overlaps = true;
            if (!overlaps) out.add(r);
            if (out.size() >= MAX_BOXES) break;
        }
        return out;
    }
}
