package com.kunukuntla.dropdownpicker;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;

/** Reads text from a screenshot (on-device OCR) and finds a keyword in it. */
final class ScreenReader {

    static final class Found {
        /** Where the matching text is, in screen coordinates. */
        final Rect box;
        final String text;
        final String keyword;

        Found(Rect box, String text, String keyword) {
            this.box = box;
            this.text = text;
            this.keyword = keyword;
        }
    }

    interface Callback {
        /** {@code found} is null if no keyword matched; {@code allText} is everything read. */
        void done(Found found, String allText, String error);
    }

    private TextRecognizer recognizer;

    /**
     * Reads {@code image} (a crop of the screen whose top-left corner is at
     * {@code offX, offY}) and reports the top-most text matching the earliest
     * keyword. Recycles {@code image} when done.
     */
    void find(Bitmap image, int offX, int offY, List<String> keywords, boolean exact, Callback cb) {
        if (recognizer == null) {
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        }
        recognizer.process(InputImage.fromBitmap(image, 0))
                .addOnSuccessListener(text -> {
                    Found found = match(text, offX, offY, keywords, exact);
                    image.recycle();
                    cb.done(found, text.getText(), null);
                })
                .addOnFailureListener(e -> {
                    image.recycle();
                    cb.done(null, "", String.valueOf(e.getMessage()));
                });
    }

    void close() {
        if (recognizer != null) recognizer.close();
        recognizer = null;
    }

    private static Found match(Text text, int offX, int offY, List<String> keywords, boolean exact) {
        // Each line on its own, and each block (a wrapped option spans several lines).
        List<String> texts = new ArrayList<>();
        List<Rect> boxes = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                if (line.getBoundingBox() == null) continue;
                texts.add(line.getText());
                boxes.add(line.getBoundingBox());
            }
            if (block.getBoundingBox() != null && block.getLines().size() > 1) {
                texts.add(block.getText().replace('\n', ' '));
                boxes.add(block.getBoundingBox());
            }
        }
        for (String k : keywords) {
            int best = -1;
            for (int i = 0; i < texts.size(); i++) {
                String t = Keywords.norm(texts.get(i));
                boolean hit = exact ? closeEnough(t, k) : t.contains(k);
                if (hit && (best < 0 || boxes.get(i).top < boxes.get(best).top)) best = i;
            }
            if (best >= 0) {
                Rect r = new Rect(boxes.get(best));
                r.offset(offX, offY);
                return new Found(r, texts.get(best), k);
            }
        }
        return null;
    }

    /** Equal, allowing a few OCR mistakes (about one letter in twelve). */
    private static boolean closeEnough(String a, String b) {
        if (a.equals(b)) return true;
        int allowed = Math.max(1, b.length() / 12);
        if (Math.abs(a.length() - b.length()) > allowed) return false;
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()] <= allowed;
    }
}
