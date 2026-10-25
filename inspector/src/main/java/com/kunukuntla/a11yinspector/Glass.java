package com.kunukuntla.a11yinspector;

import android.graphics.drawable.GradientDrawable;

/**
 * The glass look (iOS style): see-through frosted fills with a light top and a darker bottom,
 * thin bright borders and round corners. Overlays can't blur what is behind them, so the
 * frost is drawn: a soft gradient over the page, a white rim, a little shadow.
 */
final class Glass {

    private Glass() {
    }

    /** iOS indigo: the accent across the app. */
    static final int ACCENT = 0xFF5856D6;

    /** A round floating button tinted {@code colour}: see-through glass with a bright rim. */
    static GradientDrawable button(int colour, float density) {
        int rgb = colour & 0xFFFFFF;
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xC0000000 | lighten(rgb), 0x99000000 | rgb});
        d.setShape(GradientDrawable.OVAL);
        d.setStroke(Math.round(1.5f * density), 0x99FFFFFF);
        return d;
    }

    /** A dark frosted panel (cards and forms over the page). */
    static GradientDrawable darkPanel(float density) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xE62A2D3A, 0xE01B1D27});
        d.setCornerRadius(24 * density);
        d.setStroke(Math.round(1 * density), 0x4DFFFFFF);
        return d;
    }

    /** A light frosted card (on the app's own screen). */
    static GradientDrawable lightCard(float density) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xD9FFFFFF, 0xB3FFFFFF});
        d.setCornerRadius(22 * density);
        d.setStroke(Math.round(1 * density), 0xCCFFFFFF);
        return d;
    }

    /** A small glass pill tinted {@code colour} (the page badge, the ❌ box). */
    static GradientDrawable pill(int colour, float density) {
        int rgb = colour & 0xFFFFFF;
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xD0000000 | lighten(rgb), 0xB8000000 | rgb});
        d.setCornerRadius(16 * density);
        d.setStroke(Math.round(1 * density), 0x80FFFFFF);
        return d;
    }

    /** The app screen's background: an iOS-like indigo to blue wash. */
    static GradientDrawable wallpaper() {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xFF3A2FA8, 0xFF5856D6, 0xFF3D8BD9});
    }

    /** A colour a quarter of the way to white. */
    static int lighten(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        r += (255 - r) / 4;
        g += (255 - g) / 4;
        b += (255 - b) / 4;
        return (r << 16) | (g << 8) | b;
    }
}
