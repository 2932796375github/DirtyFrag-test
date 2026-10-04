package df.root;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.style.ReplacementSpan;

/** Rounded pill drawn around the version digits in the header title.
 *  Grey normally; green with white text once an update is available. */
public class VersionPillSpan extends ReplacementSpan {

    private final float scale; // version text size relative to the title
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean update;

    public VersionPillSpan(float scale) {
        this.scale = scale;
    }

    /** Flip to green/white after the GitHub update check finds a newer release. */
    public void setUpdate(boolean update) {
        this.update = update;
    }

    private int bgColor() {
        return update ? 0xFFAEEA00 : 0xFF5A5A5E; // lime on update
    }

    private int fgColor() {
        return update ? 0xFF101418 : 0xFFD9D9D9;
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end,
                       Paint.FontMetricsInt fm) {
        textPaint.set(paint);
        textPaint.setTextSize(paint.getTextSize() * scale);
        float w = textPaint.measureText(text, start, end);
        float pad = paint.getTextSize() * 0.22f;
        // Line metrics stay those of the title; the pill is drawn around the
        // baseline, so no vertical contribution is needed here.
        return (int) (w + 2 * pad);
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end,
                     float x, int top, int bottom, int baseline, Paint paint) {
        textPaint.set(paint);
        textPaint.setTextSize(paint.getTextSize() * scale);
        textPaint.setColor(fgColor());
        bgPaint.setColor(bgColor());

        float padX = paint.getTextSize() * 0.22f;
        float padY = padX * 0.5f;
        float textW = textPaint.measureText(text, start, end);
        Paint.FontMetricsInt fm = textPaint.getFontMetricsInt();
        float smallAscent = -fm.ascent;
        // Nudge the pill upward so its bottom never clips at the title view's
        // lower edge, and tighten it around the digits (no descender room).
        float raise = paint.getTextSize() * 0.14f;
        float pillTop = baseline - smallAscent - padY - raise;
        float pillBottom = baseline + padY - raise;

        canvas.drawRoundRect(
                new RectF(x, pillTop, x + textW + 2 * padX, pillBottom),
                (pillBottom - pillTop) / 2f, (pillBottom - pillTop) / 2f, bgPaint);
        canvas.drawText(text, start, end, x + padX, baseline - raise, textPaint);
    }
}
