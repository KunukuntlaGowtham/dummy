package com.kunukuntla.dropdownpicker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws what the app saw: optionally the screenshot it analysed, with the long
 * lines (yellow), the chosen underline (red), the down arrow (green), the tap
 * point (pink) and the picked option (blue) marked on top. All rects are in
 * screen coordinates.
 */
final class MarkupView extends View {

    static final int YELLOW = 0xFFFFC107;
    static final int RED = 0xFFE53935;
    static final int GREEN = 0xFF00C853;
    static final int PINK = 0xFFFF4081;
    static final int BLUE = 0xFF2979FF;

    Bitmap shot;
    List<Rect> lines = new ArrayList<>();
    List<DropdownDetector.Hit> hits = new ArrayList<>();
    Rect line, arrow, option;
    int tapX = -1, tapY = -1;
    String caption;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final int[] location = new int[2];

    MarkupView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(3 * density);
        text.setColor(Color.WHITE);
        text.setTextSize(14 * density);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (shot != null) {
            // Fit the screenshot inside the view.
            canvas.drawColor(Color.BLACK);
            float scale = Math.min(getWidth() / (float) shot.getWidth(),
                    getHeight() / (float) shot.getHeight());
            float ox = (getWidth() - shot.getWidth() * scale) / 2;
            float oy = (getHeight() - shot.getHeight() * scale) / 2;
            drawAll(canvas, scale, ox, oy);
        } else {
            // Live highlight over the real screen: undo the window's own offset.
            getLocationOnScreen(location);
            drawAll(canvas, 1, -location[0], -location[1]);
        }
    }

    /** The screenshot with the markings drawn on it, at full resolution. */
    Bitmap render() {
        Bitmap out = Bitmap.createBitmap(shot.getWidth(), shot.getHeight(), Bitmap.Config.ARGB_8888);
        drawAll(new Canvas(out), 1, 0, 0);
        return out;
    }

    private void drawAll(Canvas c, float scale, float ox, float oy) {
        if (shot != null) {
            c.drawBitmap(shot, null,
                    new RectF(ox, oy, ox + shot.getWidth() * scale, oy + shot.getHeight() * scale), null);
        }
        for (Rect r : lines) box(c, r, YELLOW, scale, ox, oy);
        for (DropdownDetector.Hit h : hits) {
            box(c, h.line, RED, scale, ox, oy);
            box(c, h.arrow, GREEN, scale, ox, oy);
        }
        box(c, line, RED, scale, ox, oy);
        box(c, arrow, GREEN, scale, ox, oy);
        box(c, option, BLUE, scale, ox, oy);
        if (tapX >= 0) {
            stroke.setColor(PINK);
            c.drawCircle(ox + tapX * scale, oy + tapY * scale, 14 * density, stroke);
        }
        if (caption != null) {
            float pad = 8 * density;
            float lineHeight = text.getTextSize() * 1.3f;
            String[] rows = caption.split("\n");
            float top = oy + 40 * density;
            fill.setColor(0xCC000000);
            c.drawRect(ox, top, c.getWidth() - ox, top + rows.length * lineHeight + 2 * pad, fill);
            for (int i = 0; i < rows.length; i++) {
                c.drawText(rows[i], ox + pad, top + pad + (i + 1) * lineHeight - text.descent(), text);
            }
        }
    }

    private void box(Canvas c, Rect r, int color, float scale, float ox, float oy) {
        if (r == null) return;
        stroke.setColor(color);
        float grow = 4 * density; // so thin lines are still visible
        c.drawRect(ox + r.left * scale - grow, oy + r.top * scale - grow,
                ox + (r.right + 1) * scale + grow, oy + (r.bottom + 1) * scale + grow, stroke);
    }
}
