package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deletes the rows you ask for: finds the row's number on the page, presses the dustbin on
 * that row (the icon that looks like the dustbin pictures - not Edit or the details arrow), then clears the two pop-ups that follow (the "are you sure" one and the
 * "deleted" one) by pressing their Yes / Delete / OK. Rows are done from the highest number
 * down, so deleting one doesn't renumber the ones still to do.
 */
final class Deleter {

    interface Listener {
        void done(String summary, String log);
    }

    /** A pop-up's button, best first. "No" and "Cancel" are never pressed. */
    private static final String[] POPUP_WORDS = {"yes", "yes delete", "yes remove", "delete",
            "remove", "confirm", "ok", "okay", "done", "got it", "close"};
    private static final int POPUPS = 2;
    /** The page's own words (read once at the start): a Yes / OK among them isn't a pop-up's. */
    private List<ScreenWords.Word> pageWords = new ArrayList<>();

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final ScreenWords words;
    private final StringBuilder log = new StringBuilder();
    private final List<String> todo = new ArrayList<>();
    private final List<String> deleted = new ArrayList<>();
    private final List<String> failed = new ArrayList<>();
    private boolean running;
    private int gen, popups, tries;
    private long start;

    Deleter(AccessibilityService service, Listener listener) {
        this.service = service;
        this.listener = listener;
        this.words = new ScreenWords(service);
    }

    boolean isRunning() {
        return running;
    }

    void start(List<Integer> rows) {
        if (running) return;
        running = true;
        gen++;
        start = SystemClock.uptimeMillis();
        log.setLength(0);
        todo.clear();
        deleted.clear();
        failed.clear();
        popups = 0;
        List<Integer> sorted = new ArrayList<>(new HashSet<>(rows));
        Collections.sort(sorted, Collections.reverseOrder());
        for (Integer n : sorted) todo.add(String.valueOf(n));
        log("Delete rows " + String.join(", ", todo) + " (highest first)");
        known.clear();
        words.read(seen -> {
            if (!running) return;
            pageWords = seen == null ? new ArrayList<>() : seen;
            nextRow();
        });
    }

    void stop(String why) {
        if (!running) return;
        running = false;
        gen++;
        log("END: " + why);
        String summary = why + "\nDeleted " + deleted.size()
                + (deleted.isEmpty() ? "" : " (rows " + String.join(", ", deleted) + ")")
                + "\nNot deleted " + failed.size()
                + (failed.isEmpty() ? "" : " (rows " + String.join(", ", failed) + ")")
                + "\nPop-ups cleared " + popups;
        listener.done(summary, "A11y Inspector - Delete run\n===========================\n" + summary
                + "\n\nSTEPS\n" + log);
    }

    // ---- one row after another --------------------------------------------------

    private void nextRow() {
        if (todo.isEmpty()) {
            stop("Done");
            return;
        }
        tries = 0;
        findRow(todo.get(0));
    }

    private void findRow(String num) {
        Target t = locate(num);
        if (t == null) {
            if (++tries <= 6) {
                log("row " + num + ": not on the page yet - scrolling down");
                scroll(true);
                later(() -> findRow(num), 650);
            } else {
                fail(num, "not found on the page");
            }
            return;
        }
        Rect r = bounds(t.number);
        if (!onScreen(t.number, r)) {
            if (++tries > 6) {
                fail(num, "its row never came on screen");
                return;
            }
            // Bring it on screen: ask the page first, then scroll towards it.
            if (tries == 1) {
                log("row " + num + ": off screen - bringing it on");
                t.number.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
            } else {
                boolean down = r.top >= screen().height() / 2;
                log("row " + num + ": scrolling " + (down ? "down" : "up") + " to it");
                scroll(down);
            }
            later(() -> findRow(num), 500);
            return;
        }
        Set<String> before = clickableKeys();
        // The dustbin you taught: found on the page straight away, no screenshot.
        Rect taughtBin = taughtBin(num, t);
        if (taughtBin != null) {
            preShot = null;
            press(num, t, taughtBin, before);
            return;
        }
        words.shot(false, shot -> {
            if (!running) return;
            if (shot == null) {
                fail(num, "couldn't take a screenshot to find its dustbin (Android 11+ needed)");
                return;
            }
            Rect bin = pickBin(num, t, shot);
            if (bin == null) {
                fail(num, "no dustbin picture on its line - nothing pressed");
                return;
            }
            preShot = shot;
            press(num, t, bin, before);
        });
    }

    private void press(String num, Target t, Rect bin, Set<String> before) {
        // The page's own button over the dustbin, if it has one; else a tap on the picture.
        AccessibilityNodeInfo node = null;
        for (AccessibilityNodeInfo c : t.buttons) {
            Rect r = bounds(c);
            if (r.contains(bin.centerX(), bin.centerY()) && (node == null || r.width() < bounds(node).width())) node = c;
        }
        log("row " + num + (t.name.isEmpty() ? "" : " (" + t.name + ")") + ": pressing its dustbin at "
                + bin.centerX() + "," + bin.centerY());
        if (node == null || !node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tap(bin.centerX(), bin.centerY());
        Taught.Button p1 = Taught.get(service, Taught.DEL_POPUP_1);
        if (p1 != null) watchTaught(num, t, before, 1, p1, 0);
        else clearPopup(num, t, before, 1, known.isEmpty() ? 8 : 16, 150);
    }

    // ---- what you taught (Teach): the dustbin and the pop-up buttons --------------------

    /** The row's dustbin, found from the one you tapped when teaching; null if not taught. */
    private Rect taughtBin(String num, Target t) {
        Taught.Button bin = Taught.get(service, Taught.DEL_BIN);
        if (bin == null) return null;
        int cx = bin.spot.centerX(), near = dp(30);
        AccessibilityNodeInfo best = null;
        int bestGap = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo c : t.buttons) {
            Rect r = bounds(c);
            if (r.width() <= 4 || r.height() <= 4) continue;
            int gap = Math.abs(r.centerX() - cx);
            if (gap > near) continue;
            if (!bin.label.isEmpty() && bin.label.equals(label(c))) gap -= near; // same name: first
            if (gap < bestGap) {
                best = c;
                bestGap = gap;
            }
        }
        if (best != null) {
            Rect r = bounds(best);
            log("row " + num + ": the dustbin you taught is its button at " + r.centerX() + "," + r.centerY()
                    + (label(best).isEmpty() ? "" : " (\"" + label(best) + "\")"));
            return r;
        }
        if (bin.hasDy) {
            Rect nr = bounds(t.number);
            int y = nr.centerY() + bin.dy;
            log("row " + num + ": the dustbin you taught is at " + cx + "," + y);
            return new Rect(cx - 2, y - 2, cx + 2, y + 2);
        }
        return null;
    }

    /**
     * Watches for pop-up {@code which} you taught: on the page (every 40 ms) when it shows
     * there, else by its look on screenshots. Presses its button the moment it is up, then
     * waits for it to go. If it doesn't come within 4 s, the words are read instead.
     */
    private void watchTaught(String num, Target t, Set<String> before, int which, Taught.Button p, long waited) {
        boolean byPage = !p.shapes.isEmpty();
        Runnable look = () -> {
            if (byPage) {
                taughtSeen(num, t, before, which, p, waited, Taught.showing(Taught.shapes(service), p));
                return;
            }
            words.shot(false, shot -> {
                if (!running) return;
                taughtSeen(num, t, before, which, p, waited + 340, shot != null && looksLike(shot, p));
            });
        };
        later(look, byPage ? 40 : 0);
    }

    private void taughtSeen(String num, Target t, Set<String> before, int which, Taught.Button p,
                            long waited, boolean up) {
        if (up) {
            int x = p.spot.centerX(), y = p.spot.centerY();
            log("pop-up " + which + ": pressing the button you taught at " + x + "," + y);
            popups++;
            later(() -> {
                tap(x, y);
                waitTaughtGone(num, t, before, which, p, 0);
            }, 60);
        } else if (waited >= 4000) {
            log("pop-up " + which + ": the one you taught didn't come - reading the screen");
            clearPopup(num, t, before, which, 4, 0);
        } else {
            watchTaught(num, t, before, which, p, waited + 40);
        }
    }

    private void waitTaughtGone(String num, Target t, Set<String> before, int which, Taught.Button p, long waited) {
        later(() -> {
            boolean up = !p.shapes.isEmpty() && Taught.showing(Taught.shapes(service), p);
            if (up && waited < 1500) {
                waitTaughtGone(num, t, before, which, p, waited + 40);
                return;
            }
            Taught.Button p2 = which == 1 ? Taught.get(service, Taught.DEL_POPUP_2) : null;
            if (p2 != null) watchTaught(num, t, before, 2, p2, 0);
            else check(num, t, 20);
        }, 40);
    }

    private static boolean looksLike(ScreenWords.Shot shot, Taught.Button p) {
        double[] g = shot.grid(p.spot);
        if (g == null || p.look == null) return false;
        double like = BinFinder.similarity(g, p.look);
        if (like < 0.95) return false;
        return p.gone == null || BinFinder.similarity(g, p.gone) < like - 0.02;
    }

    // ---- which icon is the dustbin: compared with the two dustbin pictures ------------

    /** An icon counts as a dustbin when it is at least this alike to one of the pictures. */
    private static final double BIN_MATCH = 0.88;
    private List<double[]> binPictures;

    private List<double[]> binPictures() {
        if (binPictures != null) return binPictures;
        List<double[]> out = new ArrayList<>();
        for (String name : new String[] {"bin_purple.png", "bin_basket.png"}) {
            try (java.io.InputStream in = service.getAssets().open(name)) {
                android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeStream(in);
                if (b == null) continue;
                int[] px = new int[b.getWidth() * b.getHeight()];
                b.getPixels(px, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
                out.add(BinFinder.grid(px, b.getWidth(), 0, 0, b.getWidth(), b.getHeight()));
                b.recycle();
            } catch (java.io.IOException ignored) {
            }
        }
        binPictures = out;
        return out;
    }

    private double binLikeness(ScreenWords.Shot shot, Rect r) {
        double[] g = shot.grid(r);
        if (g == null) return 0;
        double best = 0;
        for (double[] p : binPictures()) best = Math.max(best, BinFinder.similarity(g, p));
        return best;
    }

    /**
     * The dustbin on the row's line: every icon right of the number (the page's buttons and
     * any drawn icon) is compared with the dustbin pictures; the most alike wins. Null when
     * none looks like a dustbin - then nothing is pressed (not the details arrow or Edit).
     */
    private Rect pickBin(String num, Target t, ScreenWords.Shot shot) {
        Rect nr = bounds(t.number);
        int cy = nr.centerY(), half = dp(38), gap = dp(6);
        List<Rect> icons = new ArrayList<>();
        for (AccessibilityNodeInfo c : t.buttons) {
            Rect r = bounds(c);
            if (r.height() <= 4 || r.width() <= 4) continue;
            icons.add(r);
        }
        int x0 = Math.max(nr.right + dp(10), shot.w * 2 / 5), x1 = shot.w * 97 / 100;
        for (int[] b : BinFinder.blobs(shot.px, shot.w, shot.h, cy - half, cy + half, x0, x1, gap)) {
            int iw = b[2] - b[0], ih = b[3] - b[1];
            if (ih < dp(12) || ih > dp(70) || iw < ih / 2 || iw > ih * 2) continue;
            icons.add(new Rect(b[0], b[1], b[2], b[3]));
        }
        Rect best = null;
        double bestLike = 0;
        StringBuilder scores = new StringBuilder();
        for (Rect r : icons) {
            double like = binLikeness(shot, r);
            scores.append(' ').append(r.centerX()).append(',').append(r.centerY())
                    .append('=').append(Math.round(like * 100)).append('%');
            if (like > bestLike) {
                bestLike = like;
                best = r;
            }
        }
        log("row " + num + ": icons on its line (how alike a dustbin):" + (scores.length() == 0 ? " none" : scores));
        return bestLike >= BIN_MATCH ? best : null;
    }

    /** A pop-up button pressed before: where it was and how it looked. */
    private static final class Known {
        final Rect spot;
        final double[] look;
        final String text;

        Known(Rect spot, double[] look, String text) {
            this.spot = spot;
            this.look = look;
            this.text = text;
        }
    }

    /** Pop-up buttons pressed this run: later pop-ups are spotted from pixels alone (fast). */
    private final List<Known> known = new ArrayList<>();
    /** The screen just before the row's dustbin was pressed (no pop-up on it yet). */
    private ScreenWords.Shot preShot;

    /**
     * Looks for pop-up {@code which} up to {@code looksLeft} times and presses its Yes /
     * Delete / OK. A pop-up seen before is spotted from the screenshot's pixels (about 3
     * looks a second); the words are read (slower) only every third look, or while no
     * pop-up is known yet.
     */
    private void clearPopup(String num, Target t, Set<String> before, int which, int looksLeft, long delay) {
        later(() -> {
            // 1) A pop-up the page reports.
            AccessibilityNodeInfo button = popupButton(before);
            if (button != null) {
                Rect r = bounds(button);
                String what = label(button);
                log("pop-up " + which + ": pressing \"" + what + "\" at " + r.centerX() + "," + r.centerY());
                if (!button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tap(r.centerX(), r.centerY());
                Set<String> seen = new HashSet<>(before);
                seen.add(key(button, r));
                afterPopup(num, t, seen, which);
                return;
            }
            // 2) A pop-up the page doesn't report: a known button's look, or its word.
            boolean ocr = known.isEmpty() || looksLeft <= 1 || looksLeft % 3 == 0;
            words.shot(ocr, shot -> {
                if (!running) return;
                Rect hit = null;
                String what = null;
                if (shot != null) {
                    double bestLike = 0;
                    for (Known k : known) {
                        double[] g = shot.grid(k.spot);
                        if (g == null) continue;
                        double like = BinFinder.similarity(g, k.look);
                        // Not if the page looked like that there anyway, before the press.
                        double[] was = preShot == null ? null : preShot.grid(k.spot);
                        if (was != null && BinFinder.similarity(g, was) >= like - 0.02) continue;
                        if (like >= 0.95 && like > bestLike) {
                            bestLike = like;
                            hit = k.spot;
                            what = k.text + "\" (spotted by its look)";
                        }
                    }
                    if (hit == null && shot.words != null) {
                        ScreenWords.Word w = newButtonWord(shot.words, pageWords);
                        if (w != null) {
                            hit = w.box;
                            what = w.text + "\"";
                            Rect spot = grow(w.box);
                            double[] look = shot.grid(spot);
                            if (look != null) known.add(new Known(spot, look, w.text));
                        }
                    }
                }
                if (hit != null) {
                    log("pop-up " + which + ": pressing \"" + what + " at " + hit.centerX() + "," + hit.centerY());
                    tap(hit.centerX(), hit.centerY());
                    afterPopup(num, t, before, which);
                } else if (looksLeft > 1) {
                    clearPopup(num, t, before, which, looksLeft - 1, 0);
                } else {
                    if (shot == null) log("pop-up " + which + ": couldn't read the screen (Android 11+ needed)");
                    else if (which <= POPUPS) log("pop-up " + which + ": none came");
                    check(num, t, 10);
                }
            });
        }, delay);
    }

    private void afterPopup(String num, Target t, Set<String> before, int which) {
        popups++;
        // The next pop-up (the "deleted" OK), then one quick look for anything left open.
        // Wait a moment first, so the one just pressed has gone and isn't pressed twice.
        if (which < POPUPS) clearPopup(num, t, before, which + 1, known.isEmpty() ? 6 : 10, 400);
        else if (which == POPUPS) clearPopup(num, t, before, which + 1, 2, 400);
        else check(num, t, 10);
    }

    private Rect grow(Rect r) {
        Rect g = new Rect(r);
        g.inset(-Math.max(dp(6), r.width() / 4), -Math.max(dp(4), r.height() / 3));
        return g;
    }

    /** Did the row go? Its name is no longer on the page (checked a few times, 150 ms apart). */
    private void check(String num, Target t, int looksLeft) {
        if (t.name.isEmpty()) {
            log("row " + num + ": done (no name to check it by)");
            deleted.add(num);
        } else if (nameOnPage(t.name)) {
            if (looksLeft > 1) {
                later(() -> check(num, t, looksLeft - 1), 150);
                return;
            }
            log("row " + num + ": \"" + t.name + "\" is still on the page ✗");
            failed.add(num);
        } else {
            log("row " + num + ": deleted ✓ (\"" + t.name + "\" is gone)");
            deleted.add(num);
        }
        todo.remove(0);
        later(this::nextRow, 150);
    }

    private void fail(String num, String why) {
        log("row " + num + ": " + why + " ✗");
        failed.add(num);
        todo.remove(0);
        later(this::nextRow, 100);
    }

    // ---- pop-up buttons ---------------------------------------------------------

    private static int rank(String text) {
        String t = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").replaceAll(" +", " ").trim();
        for (int i = 0; i < POPUP_WORDS.length; i++) {
            if (t.equals(POPUP_WORDS[i]) || t.startsWith(POPUP_WORDS[i] + " ")) return i;
        }
        return Integer.MAX_VALUE;
    }

    private AccessibilityNodeInfo popupButton(Set<String> before) {
        AccessibilityNodeInfo best = null;
        int bestRank = Integer.MAX_VALUE;
        for (Node n : all()) {
            AccessibilityNodeInfo node = n.node;
            if (!node.isClickable() || !node.isEnabled() || !node.isVisibleToUser()) continue;
            Rect r = bounds(node);
            if (r.width() <= 0 || r.height() <= 0) continue;
            if (before.contains(key(node, r))) continue;
            String t = label(node).toLowerCase(Locale.ROOT);
            if (t.equals("no") || t.startsWith("cancel") || t.startsWith("no ")) continue;
            int rank = rank(label(node));
            if (rank == Integer.MAX_VALUE && n.inDialog) rank = 100;
            if (rank < bestRank) {
                best = node;
                bestRank = rank;
            }
        }
        return best;
    }

    /** A Yes / Delete / OK word on screen now that wasn't there before the delete press. */
    private ScreenWords.Word newButtonWord(List<ScreenWords.Word> now, List<ScreenWords.Word> base) {
        int near = dp(12);
        ScreenWords.Word best = null;
        int bestRank = Integer.MAX_VALUE;
        for (ScreenWords.Word w : now) {
            int rank = rank(w.text);
            if (rank == Integer.MAX_VALUE) continue;
            if (!w.whole) rank += 50; // a word out of a sentence: only if no button-like line
            boolean old = false;
            for (ScreenWords.Word b : base) {
                if (b.text.equalsIgnoreCase(w.text) && Math.abs(b.box.centerX() - w.box.centerX()) <= near
                        && Math.abs(b.box.centerY() - w.box.centerY()) <= near) {
                    old = true;
                    break;
                }
            }
            if (old) continue;
            if (rank < bestRank) {
                best = w;
                bestRank = rank;
            }
        }
        return best;
    }

    // ---- finding the row --------------------------------------------------------

    private static final class Target {
        final AccessibilityNodeInfo number;
        /** The page's buttons in the row (Edit, details, the dustbin ...). */
        final List<AccessibilityNodeInfo> buttons;
        final String name;

        Target(AccessibilityNodeInfo number, List<AccessibilityNodeInfo> buttons, String name) {
            this.number = number;
            this.buttons = buttons;
            this.name = name;
        }
    }

    /** The row numbered {@code num}: its delete button and the name on it, or null. */
    private Target locate(String num) {
        List<Node> nodes = all();
        Target geometric = null;
        for (Node n : nodes) {
            AccessibilityNodeInfo node = n.node;
            if (node.isClickable() || node.isEditable()) continue;
            if (!label(node).matches("\\(?" + num + "[.)]?")) continue;
            // 1) The row is its own box (the number, Edit, the bin, the name): look inside it.
            AccessibilityNodeInfo row = node.getParent();
            if (row != null && isRow(row)) {
                List<AccessibilityNodeInfo> inside = subtree(row);
                List<AccessibilityNodeInfo> buttons = new ArrayList<>();
                for (AccessibilityNodeInfo c : inside) if (c.isClickable() && !c.equals(node)) buttons.add(c);
                return new Target(node, buttons, nameIn(inside, num));
            }
            // 2) Otherwise: the bin on the same line as the number, right of it.
            if (geometric == null) geometric = sameLine(node, nodes);
        }
        return geometric;
    }

    /** A row: one row number in it and not the whole page. */
    private static boolean isRow(AccessibilityNodeInfo p) {
        List<AccessibilityNodeInfo> inside = subtree(p);
        if (inside.size() > 40) return false;
        int numbers = 0;
        for (AccessibilityNodeInfo n : inside) if (label(n).matches("\\(?\\d{1,4}[.)]?")) numbers++;
        return numbers == 1;
    }

    /** The first name-like text in the row (letters, not the number). */
    private static String nameIn(List<AccessibilityNodeInfo> inside, String num) {
        for (AccessibilityNodeInfo n : inside) {
            if (n.isClickable()) continue;
            String l = label(n);
            if (l.equals(num) || !l.matches(".*[A-Za-z]{2,}.*")) continue;
            String low = l.toLowerCase(Locale.ROOT);
            if (low.equals("male") || low.equals("female") || low.startsWith("dob") || low.contains("edit")) continue;
            return l;
        }
        return "";
    }

    private Target sameLine(AccessibilityNodeInfo number, List<Node> nodes) {
        Rect nr = bounds(number);
        if (nr.height() <= 0) return null;
        List<AccessibilityNodeInfo> buttons = new ArrayList<>();
        String name = "";
        int nameTop = Integer.MAX_VALUE;
        for (Node n : nodes) {
            Rect r = bounds(n.node);
            if (r.height() <= 0) continue;
            String l = label(n.node);
            if (n.node.isClickable()) {
                if (Math.abs(r.centerY() - nr.centerY()) > Math.max(nr.height(), r.height()) / 2 + 4) continue;
                if (r.left < nr.right || r.width() > screen().width() / 3) continue;
                buttons.add(n.node);
            } else if (r.top >= nr.bottom && r.top - nr.bottom < dp(60) && Math.abs(r.left - nr.left) < dp(20)
                    && l.matches(".*[A-Za-z]{2,}.*") && r.top < nameTop) {
                name = l;
                nameTop = r.top;
            }
        }
        return new Target(number, buttons, name);
    }

    private boolean nameOnPage(String name) {
        for (Node n : all()) if (label(n.node).equals(name)) return true;
        return false;
    }

    private static List<AccessibilityNodeInfo> subtree(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        List<AccessibilityNodeInfo> stack = new ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty() && out.size() <= 60) {
            AccessibilityNodeInfo n = stack.remove(0);
            if (n == null) continue;
            out.add(n);
            for (int i = 0; i < n.getChildCount(); i++) stack.add(n.getChild(i));
        }
        return out;
    }

    // ---- reading the page (same as Ticker) ---------------------------------------

    private static final class Node {
        final AccessibilityNodeInfo node;
        final boolean inDialog;

        Node(AccessibilityNodeInfo node, boolean inDialog) {
            this.node = node;
            this.inDialog = inDialog;
        }
    }

    private List<Node> all() {
        List<Node> out = new ArrayList<>();
        String own = service.getPackageName();
        List<AccessibilityWindowInfo> windows;
        try {
            windows = service.getWindows();
        } catch (RuntimeException e) {
            windows = new ArrayList<>();
        }
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            if (w.getType() == AccessibilityWindowInfo.TYPE_SYSTEM) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || own.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
            List<AccessibilityNodeInfo> stack = new ArrayList<>();
            List<Boolean> dialog = new ArrayList<>();
            stack.add(root);
            dialog.add(false);
            while (!stack.isEmpty() && out.size() < 6000) {
                AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
                boolean inDialog = dialog.remove(dialog.size() - 1);
                if (n == null) continue;
                String role = "";
                try {
                    CharSequence r = n.getExtras().getCharSequence("AccessibilityNodeInfo.chromeRole");
                    if (r != null) role = r.toString().toLowerCase(Locale.ROOT);
                } catch (RuntimeException ignored) {
                }
                boolean d = inDialog || role.contains("dialog") || role.contains("alertdialog");
                out.add(new Node(n, d));
                // Children in page order (the stack pops the last one first).
                for (int i = n.getChildCount() - 1; i >= 0; i--) {
                    stack.add(n.getChild(i));
                    dialog.add(d);
                }
            }
        }
        return out;
    }

    private Set<String> clickableKeys() {
        Set<String> out = new HashSet<>();
        for (Node n : all()) {
            if (!n.node.isClickable()) continue;
            out.add(key(n.node, bounds(n.node)));
        }
        return out;
    }

    private static String key(AccessibilityNodeInfo n, Rect r) {
        return label(n) + "@" + r.toShortString();
    }

    private static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        return t == null ? "" : t.toString().replace('\n', ' ').trim();
    }

    private static Rect bounds(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        try {
            n.refresh();
        } catch (RuntimeException ignored) {
        }
        n.getBoundsInScreen(r);
        return r;
    }

    private boolean onScreen(AccessibilityNodeInfo n, Rect r) {
        Rect s = screen();
        return n.isVisibleToUser() && r.width() > 4 && r.height() > 4 && r.top >= 0 && r.bottom <= s.height();
    }

    // ---- gestures and helpers -----------------------------------------------------

    private void tap(int x, int y) {
        Path p = new Path();
        p.moveTo(Math.max(0, x), Math.max(0, y));
        service.dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
    }

    /** A steady drag of about a third of the screen; {@code down}: show what is further down. */
    private void scroll(boolean down) {
        Rect s = screen();
        float a = s.height() * 2 / 3f, b = s.height() / 3f;
        Path p = new Path();
        p.moveTo(s.centerX(), down ? a : b);
        p.lineTo(s.centerX(), down ? b : a);
        service.dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 400)).build(), null, null);
    }

    private Rect screen() {
        android.util.DisplayMetrics dm = service.getResources().getDisplayMetrics();
        return new Rect(0, 0, dm.widthPixels, dm.heightPixels);
    }

    private int dp(int v) {
        return Math.round(v * service.getResources().getDisplayMetrics().density);
    }

    private void later(Runnable r, long ms) {
        int g = gen;
        handler.postDelayed(() -> {
            if (!running || g != gen) return;
            try {
                r.run();
            } catch (RuntimeException e) {
                stop("Error: " + e);
            }
        }, ms);
    }

    private void log(String line) {
        log.append(SystemClock.uptimeMillis() - start).append(" ms  ").append(line).append('\n');
    }
}
