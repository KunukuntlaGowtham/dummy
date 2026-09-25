package com.kunukuntla.dropdownpicker;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds empty (unticked) checkboxes and radio buttons in a screenshot by
 * shape: a hollow square outline, or a hollow circle, a few millimetres wide.
 */
final class FormDetector {

    static final class Result {
        /** Empty squares (checkboxes), top to bottom. */
        final List<Rect> squares = new ArrayList<>();
        /** Empty circles (radio buttons), top to bottom. */
        final List<Rect> circles = new ArrayList<>();
    }

    private FormDetector() {}

    /** {@code mmPx} is one millimetre in pixels. */
    static Result find(Bitmap bmp, float mmPx) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        boolean[] dark = new boolean[w * h];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int luma = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
            dark[i] = luma < 190;
        }

        int minSize = Math.round(mmPx * 3.5f), maxSize = Math.round(mmPx * 10);
        Result out = new Result();
        boolean[] seen = new boolean[w * h];
        int[] queue = new int[w * h];
        for (int start = 0; start < dark.length; start++) {
            if (!dark[start] || seen[start]) continue;
            int head = 0, tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            int minX = w, maxX = 0, minY = h, maxY = 0;
            boolean tooBig = false;
            while (head < tail) {
                int p = queue[head++];
                int x = p % w, y = p / w;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                if (maxX - minX > maxSize || maxY - minY > maxSize) tooBig = true;
                // 4-neighbour fill keeps separate shapes apart.
                if (x > 0) tail = push(dark, seen, queue, tail, p - 1);
                if (x < w - 1) tail = push(dark, seen, queue, tail, p + 1);
                if (y > 0) tail = push(dark, seen, queue, tail, p - w);
                if (y < h - 1) tail = push(dark, seen, queue, tail, p + w);
            }
            if (tooBig) continue;
            int bw = maxX - minX + 1, bh = maxY - minY + 1;
            if (bw < minSize || bh < minSize) continue;
            float aspect = bw / (float) bh;
            if (aspect < 0.8f || aspect > 1.25f) continue;
            float fill = tail / (float) (bw * bh);
            if (fill < 0.08f || fill > 0.55f) continue; // an outline, not a filled blob

            Rect box = new Rect(minX, minY, maxX + 1, maxY + 1);
            // Unticked: the middle is empty.
            if (darkFraction(dark, w, box.left + bw * 3 / 10, box.top + bh * 3 / 10,
                    box.right - bw * 3 / 10, box.bottom - bh * 3 / 10) > 0.05f) continue;
            // A real control has a label to its right after a clear gap (letters like "O"
            // sit right next to the following letter).
            if (!hasLabel(dark, w, box, mmPx)) continue;
            // How far in from each corner the outline starts, along the diagonal:
            // about 0 for a square, under 0.1 for a rounded square, ~0.13 for a circle.
            float depth = (cornerDepth(dark, w, box.left, box.top, 1, 1, bw, bh)
                    + cornerDepth(dark, w, box.right - 1, box.top, -1, 1, bw, bh)
                    + cornerDepth(dark, w, box.left, box.bottom - 1, 1, -1, bw, bh)
                    + cornerDepth(dark, w, box.right - 1, box.bottom - 1, -1, -1, bw, bh)) / 4;
            if (depth < 0.1f) out.squares.add(box);
            else out.circles.add(box);
        }
        out.squares.sort((a, b) -> Integer.compare(a.top, b.top));
        out.circles.sort((a, b) -> Integer.compare(a.top, b.top));
        return out;
    }

    private static boolean hasLabel(boolean[] dark, int w, Rect box, float mmPx) {
        int gapMin = Math.round(mmPx * 1.2f), reach = Math.round(mmPx * 12);
        for (int x = box.right; x < Math.min(w, box.right + reach); x++) {
            for (int y = box.top; y < box.bottom; y++) {
                if (dark[y * w + x]) return x - box.right >= gapMin;
            }
        }
        return false;
    }

    /** Fraction of the size walked diagonally inward from a corner before hitting the outline. */
    private static float cornerDepth(boolean[] dark, int w, int x, int y, int dx, int dy, int bw, int bh) {
        int steps = Math.min(bw, bh) * 3 / 10;
        for (int k = 0; k <= steps; k++) {
            if (dark[(y + dy * k) * w + x + dx * k]) return k / (float) Math.min(bw, bh);
        }
        return 0.3f;
    }

    private static int push(boolean[] dark, boolean[] seen, int[] queue, int tail, int i) {
        if (dark[i] && !seen[i]) {
            seen[i] = true;
            queue[tail++] = i;
        }
        return tail;
    }

    private static float darkFraction(boolean[] dark, int w, int x0, int y0, int x1, int y1) {
        int n = 0, total = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                total++;
                if (dark[y * w + x]) n++;
            }
        }
        return total == 0 ? 0 : n / (float) total;
    }
}
