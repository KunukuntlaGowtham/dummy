package com.kunukuntla.tickcheck;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * One look at the screen by screenshot (Android 11+): the empty checkboxes on it (found like
 * the main app's Tick, on a half-size grey copy) and the words on it (on-device OCR), with
 * where each is in screen pixels.
 */
final class Snap {

    static final class Word {
        final String text;
        final Rect box;

        Word(String text, Rect box) {
            this.text = text;
            this.box = box;
        }
    }

    static final class Result {
        final List<Rect> emptyBoxes;
        /** The words on it: only read when asked ({@link #readWords}), else empty. */
        List<Word> words = new ArrayList<>();
        final int[] lum;   // half-size grey copy, for checking a spot has a (ticked) box
        final int w, h;
        Bitmap picture;    // the full picture until the words are read (or it is dropped)

        Result(List<Rect> emptyBoxes, int[] lum, int w, int h, Bitmap picture) {
            this.emptyBoxes = emptyBoxes;
            this.lum = lum;
            this.w = w;
            this.h = h;
            this.picture = picture;
        }

        void drop() {
            if (picture != null) picture.recycle();
            picture = null;
        }

        /** How much the spot (screen pixels) varies in brightness: a box there has ink. */
        int spread(Rect r) {
            int x0 = Math.max(0, r.left / SCALE), x1 = Math.min(w, r.right / SCALE);
            int y0 = Math.max(0, r.top / SCALE), y1 = Math.min(h, r.bottom / SCALE);
            int lo = 255, hi = 0;
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    int v = lum[y * w + x];
                    if (v < lo) lo = v;
                    if (v > hi) hi = v;
                }
            }
            return hi < lo ? 0 : hi - lo;
        }
    }

    static final int SCALE = 2;

    private final AccessibilityService service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextRecognizer recognizer;

    Snap(AccessibilityService service) {
        this.service = service;
    }

    /** {@code minBox}/{@code maxBox}: the size range of a checkbox, in screen pixels. Null on failure. */
    void take(int minBox, int maxBox, Consumer<Result> done, Consumer<String> failed) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            failed.accept("screenshots need Android 11 or newer");
            return;
        }
        shoot(minBox, maxBox, done, failed, 5);
    }

    private void shoot(int minBox, int maxBox, Consumer<Result> done, Consumer<String> failed, int triesLeft) {
        service.takeScreenshot(Display.DEFAULT_DISPLAY, worker, new AccessibilityService.TakeScreenshotCallback() {
            @Override
            public void onSuccess(AccessibilityService.ScreenshotResult result) {
                Bitmap soft;
                HardwareBuffer buffer = result.getHardwareBuffer();
                try {
                    Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                    Bitmap copy = hw == null ? null : hw.copy(Bitmap.Config.ARGB_8888, false);
                    ColorSpace srgb = ColorSpace.get(ColorSpace.Named.SRGB);
                    if (copy != null && copy.getColorSpace() != null && !srgb.equals(copy.getColorSpace())) {
                        soft = Bitmap.createBitmap(copy.getWidth(), copy.getHeight(), Bitmap.Config.ARGB_8888, false, srgb);
                        new Canvas(soft).drawBitmap(copy, 0, 0, null);
                        copy.recycle();
                    } else {
                        soft = copy;
                    }
                } catch (RuntimeException e) {
                    soft = null;
                } finally {
                    buffer.close();
                }
                if (soft == null) {
                    main.post(() -> failed.accept("couldn't read the screenshot"));
                    return;
                }
                // Empty boxes, on a half-size grey copy (as the main app does).
                Bitmap half = Bitmap.createScaledBitmap(soft, soft.getWidth() / SCALE, soft.getHeight() / SCALE, true);
                int w = half.getWidth(), h = half.getHeight();
                int[] px = new int[w * h];
                half.getPixels(px, 0, w, 0, 0, w, h);
                half.recycle();
                int[] lum = new int[px.length];
                for (int k = 0; k < px.length; k++) {
                    int c = px[k];
                    lum[k] = (((c >> 16) & 255) * 299 + ((c >> 8) & 255) * 587 + (c & 255) * 114) / 1000;
                }
                List<Rect> boxes = new ArrayList<>();
                for (Rect r : BoxFinder.find(lum, w, h, minBox / SCALE, maxBox / SCALE)) {
                    boxes.add(new Rect(r.left * SCALE, r.top * SCALE, r.right * SCALE, r.bottom * SCALE));
                }
                Result r = new Result(boxes, lum, w, h, soft);
                main.post(() -> done.accept(r));
            }

            @Override
            public void onFailure(int errorCode) {
                if (triesLeft > 0) {
                    main.postDelayed(() -> shoot(minBox, maxBox, done, failed, triesLeft - 1), 350);
                } else {
                    main.post(() -> failed.accept("the phone refused the screenshot (error " + errorCode + ")"));
                }
            }
        });
    }

    /** Reads the words on the picture (slower: only when the page's own numbers aren't enough). */
    void readWords(Result r, Consumer<Result> done) {
        Bitmap bmp = r.picture;
        if (bmp == null) {
            done.accept(r);
            return;
        }
        if (recognizer == null) recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener(text -> {
                    List<Word> words = new ArrayList<>();
                    for (Text.TextBlock block : text.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            if (line.getBoundingBox() != null) words.add(new Word(line.getText(), line.getBoundingBox()));
                            if (line.getElements().size() > 1) {
                                for (Text.Element e : line.getElements()) {
                                    if (e.getBoundingBox() != null) words.add(new Word(e.getText(), e.getBoundingBox()));
                                }
                            }
                        }
                    }
                    r.words = words;
                    r.drop();
                    done.accept(r);
                })
                .addOnFailureListener(e -> {
                    r.drop();
                    done.accept(r);
                });
    }
}
