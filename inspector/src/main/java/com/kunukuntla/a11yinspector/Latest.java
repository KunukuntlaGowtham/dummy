package com.kunukuntla.a11yinspector;

/**
 * Holds the most recent read-only report the {@link InspectorService} produced
 * for another app's screen. MainActivity reads it when you return and refresh.
 * Kept in memory only - nothing is written to storage or sent anywhere.
 */
final class Latest {

    private static volatile String pkg;
    private static volatile String report;
    private static volatile long time;

    private Latest() {
    }

    static synchronized void set(String p, String r) {
        pkg = p;
        report = r;
        time = System.currentTimeMillis();
    }

    static synchronized String pkg() {
        return pkg;
    }

    static synchronized String report() {
        return report;
    }

    static synchronized long time() {
        return time;
    }
}
