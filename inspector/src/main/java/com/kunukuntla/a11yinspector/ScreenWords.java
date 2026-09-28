package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
 * Takes an accessibility screenshot: its pixels, and (when asked) every word on it read with
 * on-device OCR, with where each word is in screen pixels - so a pop-up's OK can be found even
 * when the page doesn't report the pop-up to accessibility. Android 11+.
 */
final class ScreenWords {

    static final class Word {
        final String text;
        final Rect box;
        /** A whole line by itself (a button's label), not one word out of a sentence. */
        final boolean whole;

        Word(String text, Rect box, boolean whole) {
            this.text = text;
            this.box = box;
            this.whole = whole;
        }
    }

    /** One screenshot: its pixels (sRGB, ARGB) and, if read, its words. */
    static final class Shot {
        final int[] px;
        final int w, h;
        List<Word> words;

        Shot(int[] px, int w, int h) {
            this.px = px;
            this.w = w;
            this.h = h;
        }

        /** The box's pixels as a small grid, to compare with a picture of a button. */
        double[] grid(Rect r) {
            int l = Math.max(0, r.left), t = Math.max(0, r.top);
            int ri = Math.min(w, r.right), b = Math.min(h, r.bottom);
            if (ri - l < 2 || b - t < 2) return null;
            return BinFinder.grid(px, w, l, t, ri, b);
        }
    }

    /** Android lets an accessibility service take about 3 screenshots a second. */
    private static final long GAP_MS = 340;

    private final AccessibilityService service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextRecognizer recognizer;
    private long lastShot;

    ScreenWords(AccessibilityService service) {
        this.service = service;
    }

    /** Every word on screen, or null when no screenshot or reading was possible. */
    void read(Consumer<List<Word>> done) {
        shot(true, s -> done.accept(s == null ? null : s.words));
    }

    /** A screenshot (with its words when {@code ocr}), or null when none could be taken. */
    void shot(boolean ocr, Consumer<Shot> done) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            main.post(() -> done.accept(null));
            return;
        }
        long wait = Math.max(0, lastShot + GAP_MS - SystemClock.uptimeMillis());
        main.postDelayed(() -> take(ocr, done, 5), wait);
    }

    private void take(boolean ocr, Consumer<Shot> done, int triesLeft) {
        lastShot = SystemClock.uptimeMillis();
        service.takeScreenshot(Display.DEFAULT_DISPLAY, worker,
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(AccessibilityService.ScreenshotResult result) {
                        Bitmap soft = null;
                        HardwareBuffer buffer = result.getHardwareBuffer();
                        try {
                            Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                            // A screenshot is a hardware bitmap: copy it to memory first (it
                            // can't be drawn or read directly), then into standard colours so
                            // pixels compare with the button pictures.
                            Bitmap copy = hw == null ? null : hw.copy(Bitmap.Config.ARGB_8888, false);
                            ColorSpace srgb = ColorSpace.get(ColorSpace.Named.SRGB);
                            if (copy != null && copy.getColorSpace() != null && !srgb.equals(copy.getColorSpace())) {
                                soft = Bitmap.createBitmap(copy.getWidth(), copy.getHeight(),
                                        Bitmap.Config.ARGB_8888, false, srgb);
                                new Canvas(soft).drawBitmap(copy, 0, 0, null);
                                copy.recycle();
                            } else {
                                soft = copy;
                            }
                        } catch (RuntimeException ignored) {
                            soft = null;
                        } finally {
                            buffer.close();
                        }
                        if (soft == null) {
                            main.post(() -> done.accept(null));
                            return;
                        }
                        int w = soft.getWidth(), h = soft.getHeight();
                        int[] px = new int[w * h];
                        soft.getPixels(px, 0, w, 0, 0, w, h);
                        Shot s = new Shot(px, w, h);
                        if (!ocr) {
                            soft.recycle();
                            main.post(() -> done.accept(s));
                            return;
                        }
                        Bitmap bmp = soft;
                        main.post(() -> recognise(bmp, s, done));
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        if (triesLeft > 0) main.postDelayed(() -> take(ocr, done, triesLeft - 1), GAP_MS);
                        else main.post(() -> done.accept(null));
                    }
                });
    }

    private void recognise(Bitmap bmp, Shot s, Consumer<Shot> done) {
        if (recognizer == null) recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener(text -> {
                    bmp.recycle();
                    List<Word> out = new ArrayList<>();
                    for (Text.TextBlock block : text.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            Rect lb = line.getBoundingBox();
                            if (lb != null) out.add(new Word(line.getText(), lb, true));
                            for (Text.Element e : line.getElements()) {
                                Rect b = e.getBoundingBox();
                                if (b != null && line.getElements().size() > 1) out.add(new Word(e.getText(), b, false));
                            }
                        }
                    }
                    s.words = out;
                    done.accept(s);
                })
                .addOnFailureListener(e -> {
                    bmp.recycle();
                    done.accept(null);
                });
    }
}
