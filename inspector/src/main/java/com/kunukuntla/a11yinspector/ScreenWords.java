package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
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
 * Takes an accessibility screenshot and reads every word on it (on-device OCR), with where
 * each word is in screen pixels - so a pop-up's OK can be found even when the page doesn't
 * report the pop-up to accessibility. Android 11+.
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            main.post(() -> done.accept(null));
            return;
        }
        long wait = Math.max(0, lastShot + 350 - SystemClock.uptimeMillis());
        main.postDelayed(() -> take(done, 5), wait);
    }

    private void take(Consumer<List<Word>> done, int triesLeft) {
        lastShot = SystemClock.uptimeMillis();
        service.takeScreenshot(Display.DEFAULT_DISPLAY, worker,
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(AccessibilityService.ScreenshotResult result) {
                        Bitmap soft = null;
                        HardwareBuffer buffer = result.getHardwareBuffer();
                        try {
                            Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                            if (hw != null) soft = hw.copy(Bitmap.Config.ARGB_8888, false);
                        } catch (RuntimeException ignored) {
                        } finally {
                            buffer.close();
                        }
                        if (soft == null) {
                            main.post(() -> done.accept(null));
                            return;
                        }
                        Bitmap shot = soft;
                        main.post(() -> recognise(shot, done));
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        if (triesLeft > 0) main.postDelayed(() -> take(done, triesLeft - 1), 350);
                        else main.post(() -> done.accept(null));
                    }
                });
    }

    private void recognise(Bitmap shot, Consumer<List<Word>> done) {
        if (recognizer == null) recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        recognizer.process(InputImage.fromBitmap(shot, 0))
                .addOnSuccessListener(text -> {
                    shot.recycle();
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
                    done.accept(out);
                })
                .addOnFailureListener(e -> {
                    shot.recycle();
                    done.accept(null);
                });
    }
}
