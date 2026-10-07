package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Read-only evidence: a tree cannot prove that nothing is drawn over the page. */
final class PopupDiagnostics {
    private static final java.util.concurrent.Executor WORKER = Executors.newSingleThreadExecutor();

    static Map<String, String> snapshot(AccessibilityService service) {
        Map<String, String> out = new LinkedHashMap<>();
        int index = 0;
        for (AccessibilityNodeInfo n : Page.nodes(service)) {
            String uid = Build.VERSION.SDK_INT >= 33 ? n.getUniqueId() : null;
            String key = n.getWindowId() + ":" + (uid == null ? "index-" + index : "uid-" + uid);
            index++;
            out.put(key, n.getClassName() + " role=" + Page.role(n) + " id=" + n.getViewIdResourceName()
                    + " text=" + Page.label(n) + " bounds=" + Page.bounds(n).toShortString()
                    + " visible=" + n.isVisibleToUser() + " enabled=" + n.isEnabled()
                    + " checked=" + n.isChecked() + " selected=" + n.isSelected()
                    + " clickable=" + n.isClickable() + " children=" + n.getChildCount());
        }
        return out;
    }

    static String changes(Map<String, String> before, Map<String, String> after) {
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, String> e : after.entrySet()) {
            String old = before.get(e.getKey());
            if (e.getValue().equals(old)) continue;
            count++;
            if (count <= 60) out.append(old == null ? "+ " : "~ ").append(e.getKey()).append(' ')
                    .append(e.getValue()).append('\n');
        }
        for (Map.Entry<String, String> e : before.entrySet()) {
            if (after.containsKey(e.getKey())) continue;
            count++;
            if (count <= 60) out.append("- ").append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        }
        if (count > 60) out.append("... ").append(count - 60).append(" further changes omitted\n");
        return out.toString();
    }

    static String treeEvidence(AccessibilityService service) {
        StringBuilder out = new StringBuilder("\nPOPUP DIAGNOSTICS (evidence, not a guarantee)\n");
        out.append("No dialog/OK node does not mean no popup. WebViews can hide the entire popup.\n")
                .append("An empty large element is only a possible backdrop; compare before/after and the screenshot.\n");
        for (AccessibilityWindowInfo w : Page.windows(service)) {
            out.append("Window ").append(w.getId()).append(" layer=").append(w.getLayer())
                    .append(" active=").append(w.isActive()).append('\n');
        }
        List<AccessibilityNodeInfo> all = Page.nodes(service);
        out.append("Possible empty backdrop rectangles: ").append(Page.coverKeys(service, all)).append('\n');
        Page.Popup p = Page.popup(service, null);
        out.append("Accessible popup target: ").append(p == null ? "none exposed" : p.how + " / "
                + (p.button == null ? "dismiss action" : Page.label(p.button))).append('\n');
        out.append("Clickable / dismissable nodes (including unnamed and hidden):\n");
        int count = 0;
        for (AccessibilityNodeInfo n : all) {
            if (!n.isClickable() && !n.isDismissable()) continue;
            if (++count > 150) { out.append("... truncated at 150 targets\n"); break; }
            out.append("  ").append(n.getClassName()).append(" role=").append(Page.role(n))
                    .append(" id=").append(n.getViewIdResourceName()).append(" text=\"")
                    .append(Page.label(n)).append("\" @").append(Page.bounds(n).toShortString())
                    .append(" visible=").append(n.isVisibleToUser()).append(" enabled=").append(n.isEnabled())
                    .append(" actions=").append(n.getActionList()).append('\n');
        }
        return out.toString();
    }

    /** Saves one user-requested screenshot locally; never uploads it or presses anything. */
    static void capture(AccessibilityService service, Consumer<String> done) {
        if (Build.VERSION.SDK_INT < 30) {
            done.accept("\nVISUAL EVIDENCE: screenshot capture requires Android 11+.\n");
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        boolean[] completed = {false}; // accessed only on main
        Consumer<String> finish = text -> {
            if (completed[0]) return;
            completed[0] = true;
            done.accept(text);
        };
        main.postDelayed(() -> finish.accept("\nVISUAL EVIDENCE: screenshot timed out; popup status unknown.\n"), 5000);
        List<Rect> excluded = new PurpleFinder(service).excluded(null);
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, WORKER, new AccessibilityService.TakeScreenshotCallback() {
                @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                    Bitmap hw = null, bmp = null;
                    HardwareBuffer buffer = result.getHardwareBuffer();
                    String text;
                    try {
                        hw = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (hw == null) throw new IllegalStateException("No readable screenshot");
                        bmp = hw.copy(Bitmap.Config.ARGB_8888, false);
                        if (bmp == null) throw new IllegalStateException("No software bitmap");
                        Rect candidate = PurpleFinder.scan(bmp, excluded);
                        text = "\nVISUAL EVIDENCE\nScreenshot: " + bmp.getWidth() + "x" + bmp.getHeight()
                                + "\nPurple-button candidate: " + (candidate == null ? "none matched; other colours/designs may still be a popup"
                                : candidate.toShortString() + " (colour/shape heuristic, not text recognition)")
                                + "\n" + save(service, bmp)
                                + "\nAttach the PNG along with this report. Captured after the tree; animations may differ.\n";
                    } catch (Exception e) {
                        text = "\nVISUAL EVIDENCE: unavailable (" + e + "); popup status unknown.\n";
                    } finally {
                        if (bmp != null) bmp.recycle();
                        if (hw != null) hw.recycle();
                        buffer.close();
                    }
                    String message = text;
                    main.post(() -> finish.accept(message));
                }
                @Override public void onFailure(int code) {
                    main.post(() -> finish.accept("\nVISUAL EVIDENCE: screenshot failed, Android error " + code
                            + "; popup status unknown. Secure screens may block screenshots.\n"));
                }
            });
        } catch (RuntimeException e) {
            finish.accept("\nVISUAL EVIDENCE: " + e + "; popup status unknown.\n");
        }
    }

    private static String save(AccessibilityService service, Bitmap bmp) throws Exception {
        String name = "a11y-popup-" + System.currentTimeMillis() + ".png";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, "image/png");
        values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/A11yInspector");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = service.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new java.io.IOException("Couldn't create screenshot file");
        try {
            try (OutputStream out = service.getContentResolver().openOutputStream(uri)) {
                if (out == null || !bmp.compress(Bitmap.CompressFormat.PNG, 100, out))
                    throw new java.io.IOException("Couldn't write screenshot");
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            service.getContentResolver().update(uri, values, null, null);
            return "PNG saved: Download/A11yInspector/" + name;
        } catch (Exception e) {
            service.getContentResolver().delete(uri, null, null);
            throw e;
        }
    }
}
