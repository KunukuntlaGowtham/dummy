package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Where the page in front comes from: the app's own (native) screen, a web page in a browser,
 * a web page in a Custom Tab, or a web page shown inside the app (a WebView) - and what the app
 * is built with (Android views, Jetpack Compose, Flutter, React Native, Unity ...). Fed every
 * element during a scan; reads only.
 */
final class Origin {

    /** Browsers by package: their name and the id of their address bar. */
    private static final String[][] BROWSERS = {
            {"com.android.chrome", "Chrome"}, {"com.chrome.beta", "Chrome Beta"}, {"com.chrome.dev", "Chrome Dev"},
            {"org.mozilla.firefox", "Firefox"}, {"org.mozilla.fenix", "Firefox"}, {"org.mozilla.focus", "Firefox Focus"},
            {"com.sec.android.app.sbrowser", "Samsung Internet"}, {"com.microsoft.emmx", "Edge"},
            {"com.brave.browser", "Brave"}, {"com.opera.browser", "Opera"}, {"com.opera.mini.native", "Opera Mini"},
            {"com.duckduckgo.mobile.android", "DuckDuckGo"}, {"com.vivaldi.browser", "Vivaldi"},
            {"com.kiwibrowser.browser", "Kiwi"}, {"com.UCMobile.intl", "UC Browser"},
            {"com.mi.globalbrowser", "Mi Browser"}, {"com.heytap.browser", "HeyTap Browser"},
            {"com.yandex.browser", "Yandex"}, {"com.google.android.googlequicksearchbox", "Google app"}};

    /** Elements of each kind of origin: web, views, compose, flutter, react-native, unity. */
    final Map<String, Integer> counts = new TreeMap<>();
    /** Web roles seen ("button", "checkBox", "link" ...) and how many. */
    private final Map<String, Integer> webRoles = new TreeMap<>();
    /** Extra data keys the web elements carry (HTML tag, input type ...). */
    private final Map<String, Integer> webExtras = new TreeMap<>();
    private final Set<String> links = new LinkedHashSet<>();
    private final List<String> webViews = new ArrayList<>();
    private long webArea;
    private String url = "", urlFrom = "";

    /** The kind of origin a child of {@code n} has, given its parent's ({@code parent}). */
    static String engineOf(AccessibilityNodeInfo n, String cls, String parent) {
        if ("web".equals(parent)) return "web";
        if (cls.contains("WebView") || hasChromeRole(n)) return "web";
        String c = cls.toLowerCase(Locale.ROOT);
        if (c.contains("androidcomposeview") || c.contains("compose.ui")) return "compose";
        if (c.contains("flutter")) return "flutter";
        if (c.startsWith("com.facebook.react") || c.contains("reactroot") || c.contains("reactview")
                || c.contains("reactviewgroup")) return "react-native";
        if (c.contains("unityplayer") || c.contains("unity3d")) return "unity";
        if (c.contains("xamarin") || c.startsWith("crc64")) return "xamarin";
        return parent == null ? "views" : parent;
    }

    private static boolean hasChromeRole(AccessibilityNodeInfo n) {
        try {
            return n.getExtras().containsKey("AccessibilityNodeInfo.chromeRole");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Notes one element and the origin it has. */
    void add(AccessibilityNodeInfo n, String cls, String engine) {
        // The phone's own status bar and system screens are not the page.
        CharSequence pkg = n.getPackageName();
        if (pkg != null && "com.android.systemui".contentEquals(pkg)) return;
        counts.merge(engine, 1, Integer::sum);
        Rect r = Page.bounds(n);
        String id = n.getViewIdResourceName();
        if (id != null && url.isEmpty()) {
            String i = id.toLowerCase(Locale.ROOT);
            if (i.endsWith("url_bar") || i.endsWith("url_field") || i.contains("omnibar") || i.contains("location_bar")
                    || i.contains("toolbar_url") || i.contains("address_bar") || i.endsWith("/url")) {
                String t = Page.label(n);
                if (!t.isEmpty()) {
                    url = t;
                    urlFrom = "the address bar";
                }
            }
        }
        if (cls.contains("WebView")) {
            String title = Page.label(n);
            webViews.add(cls.substring(cls.lastIndexOf('.') + 1) + (title.isEmpty() ? "" : " \"" + cut(title, 60) + "\"")
                    + " @" + r.toShortString() + (n.isVisibleToUser() ? "" : " [not on screen]"));
            if (n.isVisibleToUser()) webArea += (long) Math.max(0, r.width()) * Math.max(0, r.height());
        }
        if (!"web".equals(engine)) return;
        String role = Page.role(n);
        if (!role.isEmpty()) webRoles.merge(role, 1, Integer::sum);
        Bundle extras;
        try {
            extras = n.getExtras();
        } catch (RuntimeException e) {
            return;
        }
        if (extras == null) return;
        for (String k : extras.keySet()) {
            webExtras.merge(k.replace("AccessibilityNodeInfo.", ""), 1, Integer::sum);
            Object v;
            try {
                v = extras.get(k);
            } catch (RuntimeException e) {
                continue;
            }
            String s = String.valueOf(v);
            if ((s.startsWith("http://") || s.startsWith("https://")) && links.size() < 40) links.add(cut(s, 120));
        }
    }

    /** The verdict for the card and the full section for the report. */
    String[] report(AccessibilityService service, String pkg, String screenOpen, int total) {
        android.util.DisplayMetrics dm = service.getResources().getDisplayMetrics();
        long screen = (long) dm.widthPixels * dm.heightPixels;
        int web = counts.getOrDefault("web", 0);
        int webPct = total == 0 ? 0 : web * 100 / total;
        int areaPct = screen == 0 ? 0 : (int) Math.min(100, webArea * 100 / screen);
        String browser = browserName(pkg);
        String screenName = screenOpen == null ? "" : screenOpen;
        boolean customTab = screenName.toLowerCase(Locale.ROOT).contains("customtab");
        boolean twa = screenName.toLowerCase(Locale.ROOT).contains("trustedweb");
        String framework = framework(service, pkg);
        String native_ = nativeEngine();
        // React Native draws with plain Android view groups: known from the app's own parts.
        if (native_.equals("Android views") && framework.contains("React Native")) native_ = "React Native";
        if (native_.equals("Android views") && framework.contains("Flutter")) native_ = "Flutter";

        String verdict;
        if (browser != null && customTab) {
            verdict = "🌐 Web page in a Custom Tab (" + browser + ") - opened from another app";
        } else if (browser != null) {
            verdict = "🌐 Web page in the " + browser + " browser";
        } else if (twa) {
            verdict = "🌐 Web app (Trusted Web Activity) - a website shown full screen as an app";
        } else if (web > 0 && (areaPct >= 60 || webPct >= 60)) {
            verdict = "🌐 Web page inside the app (WebView covers " + areaPct + "% of the screen)"
                    + (framework.contains("Capacitor") ? " - a web app wrapped as an app (Capacitor/Cordova)" : "");
        } else if (web > 0) {
            verdict = "📱 App screen (" + native_ + ") with a web part (WebView, " + areaPct + "% of the screen)";
        } else {
            verdict = "📱 App's own screen - native (" + native_ + "), nothing loaded from the web";
        }

        StringBuilder sum = new StringBuilder(verdict).append('\n');
        if (!url.isEmpty()) sum.append("   ").append(cut(url, 70)).append('\n');

        StringBuilder rep = new StringBuilder();
        rep.append("WHERE THIS PAGE COMES FROM\n");
        rep.append("  ").append(verdict).append('\n');
        rep.append("  App: ").append(pkg).append(browser != null ? " (a browser: " + browser + ")" : "").append('\n');
        if (!screenName.isEmpty()) rep.append("  Screen (activity): ").append(screenName).append('\n');
        rep.append("  App built with: ").append(framework).append('\n');
        rep.append("  This screen drawn by: ").append(native_).append(web > 0 ? " + web" : "").append('\n');
        rep.append("  Elements by origin:");
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            rep.append(' ').append(e.getKey()).append(' ').append(e.getValue())
                    .append(" (").append(total == 0 ? 0 : e.getValue() * 100 / total).append("%)");
        }
        rep.append('\n');
        if (!url.isEmpty()) rep.append("  Address: ").append(url).append(" (from ").append(urlFrom).append(")\n");
        if (!webViews.isEmpty()) {
            rep.append("  Web views (").append(webViews.size()).append("), on screen ").append(areaPct).append("% of it:\n");
            for (String w : webViews) rep.append("    ").append(w).append('\n');
        }
        if (web > 0) {
            rep.append("  Web roles: ").append(joinCounts(webRoles)).append('\n');
            rep.append("  Web extras (what the page tells about its elements): ").append(joinCounts(webExtras)).append('\n');
            if (!links.isEmpty()) {
                rep.append("  Addresses on the page (").append(links.size()).append("):\n");
                for (String l : links) rep.append("    ").append(l).append('\n');
            }
            if (web < 40) {
                rep.append("  ⚠️ The web part reports only ").append(web).append(" elements: the page may still be "
                        + "loading, or hide its controls - a deep scan wakes it first.\n");
            }
        }
        rep.append("  How to tell: web elements sit inside a WebView (or carry Chrome's role data); "
                + "native ones are the app's own Android views / Compose / Flutter ... nodes.\n");
        return new String[] {sum.toString(), rep.toString()};
    }

    /** The app's own (non-web) way of drawing, from the elements counted. */
    private String nativeEngine() {
        String best = "Android views";
        int n = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getKey().equals("web") || e.getKey().equals("views")) continue;
            if (e.getValue() > n) {
                n = e.getValue();
                best = name(e.getKey());
            }
        }
        return best;
    }

    private static String name(String engine) {
        switch (engine) {
            case "compose": return "Jetpack Compose";
            case "flutter": return "Flutter";
            case "react-native": return "React Native";
            case "unity": return "Unity";
            case "xamarin": return "Xamarin / .NET MAUI";
            default: return "Android views";
        }
    }

    private static String browserName(String pkg) {
        for (String[] b : BROWSERS) if (b[0].equals(pkg)) return b[1];
        String p = pkg.toLowerCase(Locale.ROOT);
        return p.contains("browser") ? pkg : null;
    }

    private static String framework(AccessibilityService service, String pkg) {
        try {
            PackageInfo p = service.getPackageManager().getPackageInfo(pkg, PackageManager.GET_ACTIVITIES
                    | PackageManager.GET_SERVICES | PackageManager.GET_META_DATA);
            return RawScan.framework(p);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return "unknown (app details not readable)";
        }
    }

    private static String joinCounts(Map<String, Integer> m) {
        if (m.isEmpty()) return "none";
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : m.entrySet()) out.add(e.getKey() + " " + e.getValue());
        return String.join(", ", out);
    }

    private static String cut(String s, int max) {
        s = s.replace('\n', ' ');
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
