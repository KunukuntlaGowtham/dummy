package com.kunukuntla.engineapi;

import android.accessibilityservice.AccessibilityService;

/**
 * The shell (the app you install) knows only this interface. The real logic lives in a separate
 * engine, delivered from the server to a licensed phone and loaded at runtime - never shipped in
 * the APK. The shell calls run(); the engine does the work.
 */
public interface SecureEngine {

    /** A short result line. {@code arg} carries options (e.g. "burst"). {@code log} takes step notes. */
    String run(AccessibilityService service, String arg, Log log);

    interface Log {
        void line(String s);
    }
}
