package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The web page shown in the app, written out as HTML-like code from what the browser engine
 * reports to accessibility for each element: its tag (from its role), id, text, link, checked /
 * disabled / hidden state, display, hint and place. It is not the page's real source (scripts,
 * styles and anything the page hides from accessibility aren't reported) but shows its
 * structure as the browser exposes it. Reads only.
 */
final class PageCode {

    private PageCode() {
    }

    private static final int MAX = 4000;

    static String write(AccessibilityService service) {
        StringBuilder sb = new StringBuilder();
        int[] count = {0};
        int pages = 0;
        for (AccessibilityWindowInfo w : Page.windows(service)) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            List<AccessibilityNodeInfo> webRoots = new ArrayList<>();
            findWeb(root, webRoots, 0);
            for (AccessibilityNodeInfo web : webRoots) {
                pages++;
                String title = Page.label(web);
                sb.append("<!-- web page ").append(pages).append(title.isEmpty() ? "" : ": " + title)
                        .append(" @").append(Page.bounds(web).toShortString()).append(" -->\n");
                for (int i = 0; i < web.getChildCount(); i++) element(sb, web.getChild(i), 0, count);
                sb.append('\n');
            }
        }
        if (pages == 0) return "PAGE CODE\n  No web page on this screen - it is the app's own (native) screen.\n";
        return "PAGE CODE (as the browser reports it: " + count[0] + " elements"
                + (count[0] >= MAX ? ", cut short" : "") + ")\n"
                + "<!-- Rebuilt from accessibility: tags from roles, ids, texts, links and states.\n"
                + "     Scripts, styles and anything the page hides from accessibility aren't in it. -->\n\n" + sb;
    }

    /** The web views' top elements (the browser's "rootWebArea"), searched through the app's views. */
    private static void findWeb(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 60) return;
        String cls = String.valueOf(n.getClassName());
        String role = Page.role(n);
        if (role.equals("rootWebArea") || (cls.contains("WebView") && !role.isEmpty())) {
            out.add(n);
            return;
        }
        for (int i = 0; i < n.getChildCount(); i++) findWeb(n.getChild(i), out, depth + 1);
    }

    private static void element(StringBuilder sb, AccessibilityNodeInfo n, int depth, int[] count) {
        if (n == null || count[0] >= MAX) return;
        count[0]++;
        String role = Page.role(n);
        String tag = tag(n, role);
        String text = n.getText() == null ? "" : n.getText().toString().replace('\n', ' ').trim();
        CharSequence desc = n.getContentDescription();
        StringBuilder padding = new StringBuilder();
        for (int i = Math.min(depth, 30); i > 0; i--) padding.append("  ");
        String pad = padding.toString();

        StringBuilder a = new StringBuilder();
        String id = n.getViewIdResourceName();
        if (id != null && !id.isEmpty()) attr(a, "id", id);
        if (!role.isEmpty() && !roleMatchesTag(role, tag)) attr(a, "role", role);
        if (tag.equals("input")) attr(a, "type", inputType(n, role));
        Bundle ex = extras(n);
        String url = ex == null ? null : ex.getString("AccessibilityNodeInfo.targetUrl");
        boolean picture = role.equalsIgnoreCase("image") || role.equalsIgnoreCase("img");
        if (url != null && !url.isEmpty()) attr(a, tag.equals("img") || picture ? "src" : "href", url);
        if (desc != null && desc.length() > 0 && !desc.toString().equals(text)) attr(a, tag.equals("img") ? "alt" : "aria-label", desc.toString());
        String hint = ex == null ? null : ex.getString("AccessibilityNodeInfo.hint");
        if (hint == null && Build.VERSION.SDK_INT >= 26 && n.getHintText() != null) hint = n.getHintText().toString();
        if (hint != null && !hint.isEmpty() && !hint.equals(text)) attr(a, "placeholder", hint);
        if (n.isCheckable() && n.isChecked()) a.append(" checked");
        if (n.isSelected()) a.append(" selected");
        if (!n.isEnabled()) a.append(" disabled");
        if (n.isPassword()) attr(a, "type", "password");
        if (n.isClickable()) a.append(" onclick");
        String display = ex == null ? null : ex.getString("AccessibilityNodeInfo.cssDisplay");
        if (display != null && !display.isEmpty() && !display.equals("block") && !display.equals("inline")) attr(a, "style", "display:" + display);
        if (Build.VERSION.SDK_INT >= 28 && n.isHeading() && !tag.matches("h\\d")) a.append(" heading");
        CharSequence err = n.getError();
        if (err != null && err.length() > 0) attr(a, "aria-errormessage", err.toString());
        if (n.getLiveRegion() != 0) attr(a, "aria-live", n.getLiveRegion() == 1 ? "polite" : "assertive");
        Rect r = Page.bounds(n);
        boolean hidden = !n.isVisibleToUser() || r.width() <= 0 || r.height() <= 0;
        if (hidden) a.append(" hidden");

        String place = "  <!-- @" + r.left + "," + r.top + " " + r.width() + "x" + r.height() + " -->";
        boolean selfClosing = tag.equals("input") || tag.equals("img");
        if (selfClosing) {
            sb.append(pad).append('<').append(tag).append(a).append(">").append(text.isEmpty() ? "" : " " + cut(text))
                    .append(place).append('\n');
            return;
        }
        if (tag.equals("#text")) {
            sb.append(pad).append(cut(text.isEmpty() && desc != null ? desc.toString() : text)).append('\n');
            return;
        }
        if (n.getChildCount() == 0) {
            sb.append(pad).append('<').append(tag).append(a).append('>').append(cut(text))
                    .append("</").append(tag).append('>').append(place).append('\n');
            return;
        }
        sb.append(pad).append('<').append(tag).append(a).append('>').append(place).append('\n');
        if (!text.isEmpty() && !childrenSay(n, text)) sb.append(pad).append("  ").append(cut(text)).append('\n');
        for (int i = 0; i < n.getChildCount(); i++) element(sb, n.getChild(i), depth + 1, count);
        sb.append(pad).append("</").append(tag).append(">\n");
    }

    /** The HTML tag for the browser's role. */
    private static String tag(AccessibilityNodeInfo n, String role) {
        String r = role.toLowerCase(Locale.ROOT);
        switch (r) {
            case "button": case "togglebutton": case "popupbutton": return "button";
            case "checkbox": case "radiobutton": case "switch": case "textfield": case "searchbox":
            case "spinbutton": case "slider": case "textfieldwithcombobox": return "input";
            case "combobox": case "listbox": return "select";
            case "listboxoption": case "menuitem": return "option";
            case "link": return "a";
            case "image": case "img": return n.isClickable() ? "button" : "img";
            case "statictext": case "inlinetextbox": return "#text";
            case "labeltext": return "label";
            case "main": return "main";
            case "navigation": return "nav";
            case "banner": case "header": return "header";
            case "contentinfo": case "footer": return "footer";
            case "list": return "ul";
            case "listitem": return "li";
            case "paragraph": return "p";
            case "form": return "form";
            case "table": return "table";
            case "row": return "tr";
            case "cell": case "gridcell": return "td";
            case "columnheader": case "rowheader": return "th";
            case "dialog": case "alertdialog": return "dialog";
            case "section": case "region": return "section";
            case "article": return "article";
            case "heading": return "h" + Math.max(1, Math.min(6, headingLevel(n)));
            case "lineBreak": case "linebreak": return "br";
            default: break;
        }
        String cls = String.valueOf(n.getClassName());
        if (cls.endsWith("EditText")) return "input";
        if (cls.endsWith("CheckBox")) return "input";
        if (cls.endsWith("Button")) return "button";
        return "div";
    }

    private static boolean roleMatchesTag(String role, String tag) {
        String r = role.toLowerCase(Locale.ROOT);
        return r.equals(tag) || r.equals("genericcontainer") || r.equals("statictext") || r.equals("labeltext")
                || r.equals("checkbox") || r.equals("textfield") || r.equals("listitem") || r.equals("list")
                || r.equals("paragraph") || r.equals("heading") || r.equals("link") || r.equals("image")
                || r.equals("main") || r.equals("dialog") || r.equals("combobox");
    }

    private static String inputType(AccessibilityNodeInfo n, String role) {
        String r = role.toLowerCase(Locale.ROOT);
        if (r.equals("checkbox")) return "checkbox";
        if (r.equals("radiobutton")) return "radio";
        if (r.equals("slider")) return "range";
        if (r.equals("spinbutton")) return "number";
        if (r.equals("searchbox")) return "search";
        if (r.equals("switch")) return "checkbox\" role=\"switch";
        if (n.isPassword()) return "password";
        int t = n.getInputType() & 0xF;
        if (t == 2) return "number";
        if (t == 3) return "tel";
        if (t == 4) return "date";
        return "text";
    }

    /** The browser doesn't say a heading's level: h2 for all. */
    private static int headingLevel(AccessibilityNodeInfo n) {
        return 2;
    }

    private static boolean childrenSay(AccessibilityNodeInfo n, String text) {
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null && text.equals(Page.label(c))) return true;
        }
        return false;
    }

    private static Bundle extras(AccessibilityNodeInfo n) {
        try {
            return n.getExtras();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void attr(StringBuilder a, String name, String value) {
        a.append(' ').append(name).append("=\"").append(cut(value).replace("\"", "&quot;")).append('"');
    }

    private static String cut(String s) {
        s = s.replace('\n', ' ');
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}
