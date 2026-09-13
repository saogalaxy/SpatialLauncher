package com.spatiallauncher.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen overlay for placing / moving / resizing OCR read boxes.
 */
public final class OcrRegionOverlayView extends View {
    public interface Listener {
        void onRegionsChanged(List<OcrRegion> regions);

        void onEditFinished(List<OcrRegion> regions);
    }

    private static final float HANDLE = 28f;
    private static final float MIN_PX = 48f;

    private final ArrayList<OcrRegion> regions = new ArrayList<>();
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final Path clearPath = new Path();

    private Listener listener;
    private int selected = -1;
    private Mode mode = Mode.NONE;
    private float lastX;
    private float lastY;
    private float createStartX;
    private float createStartY;

    private enum Mode {
        NONE,
        MOVE,
        RESIZE_TL,
        RESIZE_TR,
        RESIZE_BL,
        RESIZE_BR,
        CREATE
    }

    public OcrRegionOverlayView(Context context) {
        super(context);
        init();
    }

    public OcrRegionOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setClickable(true);
        setFocusable(true);
        dimPaint.setColor(0x99000000);
        boxFill.setColor(0x3322C55E);
        boxStroke.setStyle(Paint.Style.STROKE);
        boxStroke.setStrokeWidth(3f);
        boxStroke.setColor(0xCC22C55E);
        selectedStroke.setStyle(Paint.Style.STROKE);
        selectedStroke.setStrokeWidth(5f);
        selectedStroke.setColor(0xFF4ADE80);
        handlePaint.setColor(0xFFF8FAFC);
        handleStroke.setStyle(Paint.Style.STROKE);
        handleStroke.setStrokeWidth(2f);
        handleStroke.setColor(0xFF166534);
        hintPaint.setColor(0xFFE8ECF6);
        hintPaint.setTextSize(36f);
        hintPaint.setTextAlign(Paint.Align.CENTER);
        labelBg.setColor(0xE8166534);
        labelPaint.setColor(0xFFF8FAFC);
        labelPaint.setTextSize(28f);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setFakeBoldText(true);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setRegions(List<OcrRegion> next) {
        regions.clear();
        if (next != null) {
            for (OcrRegion r : next) {
                if (r != null) {
                    regions.add(r.copy());
                }
            }
        }
        if (regions.isEmpty()) {
            regions.add(OcrRegion.defaultSubtitleBand());
        }
        selected = Math.min(selected, regions.size() - 1);
        invalidate();
    }

    List<OcrRegion> getRegions() {
        ArrayList<OcrRegion> out = new ArrayList<>(regions.size());
        for (OcrRegion r : regions) {
            out.add(r.copy());
        }
        return out;
    }

    void addRegion() {
        if (regions.size() >= OcrRegionStore.MAX_REGIONS) {
            return;
        }
        float w = 0.5f;
        float h = 0.18f;
        float left = 0.25f;
        float top = 0.4f + regions.size() * 0.05f;
        if (top + h > 0.95f) {
            top = 0.35f;
        }
        regions.add(new OcrRegion(left, top, left + w, top + h));
        selected = regions.size() - 1;
        notifyChanged();
        invalidate();
    }

    void deleteSelected() {
        if (selected < 0 || selected >= regions.size()) {
            return;
        }
        regions.remove(selected);
        selected = regions.isEmpty() ? -1 : Math.min(selected, regions.size() - 1);
        notifyChanged();
        invalidate();
    }

    void resetToDefault() {
        regions.clear();
        regions.add(OcrRegion.defaultSubtitleBand());
        selected = 0;
        notifyChanged();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        // Dim full screen, then punch clear holes for each region.
        clearPath.reset();
        clearPath.setFillType(Path.FillType.EVEN_ODD);
        clearPath.addRect(0, 0, w, h, Path.Direction.CW);
        for (OcrRegion r : regions) {
            toPx(r, tmp, w, h);
            clearPath.addRect(tmp, Path.Direction.CCW);
        }
        canvas.drawPath(clearPath, dimPaint);

        for (int i = 0; i < regions.size(); i++) {
            toPx(regions.get(i), tmp, w, h);
            canvas.drawRect(tmp, boxFill);
            canvas.drawRect(tmp, i == selected ? selectedStroke : boxStroke);
            drawOrderLabel(canvas, tmp, i + 1);
            if (i == selected) {
                drawHandle(canvas, tmp.left, tmp.top);
                drawHandle(canvas, tmp.right, tmp.top);
                drawHandle(canvas, tmp.left, tmp.bottom);
                drawHandle(canvas, tmp.right, tmp.bottom);
            }
        }

        if (regions.isEmpty()) {
            canvas.drawText("Drag to draw a read box", w / 2f, h / 2f, hintPaint);
        }
    }

    private void drawOrderLabel(Canvas canvas, RectF box, int order) {
        float size = dp(22);
        float cx = box.left + size;
        float cy = box.top + size;
        canvas.drawCircle(cx, cy, size * 0.72f, labelBg);
        Paint.FontMetrics fm = labelPaint.getFontMetrics();
        float textY = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(String.valueOf(order), cx, textY, labelPaint);
    }

    private void drawHandle(Canvas canvas, float x, float y) {
        float r = HANDLE / 2f;
        canvas.drawCircle(x, y, r, handlePaint);
        canvas.drawCircle(x, y, r, handleStroke);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mode = Mode.NONE;
                if (selected >= 0 && selected < regions.size()) {
                    toPx(regions.get(selected), tmp, w, h);
                    Mode corner = hitHandle(tmp, x, y);
                    if (corner != Mode.NONE) {
                        mode = corner;
                        lastX = x;
                        lastY = y;
                        return true;
                    }
                }
                int hit = hitRegion(x, y, w, h);
                if (hit >= 0) {
                    selected = hit;
                    mode = Mode.MOVE;
                    lastX = x;
                    lastY = y;
                    invalidate();
                    return true;
                }
                if (regions.size() < OcrRegionStore.MAX_REGIONS) {
                    mode = Mode.CREATE;
                    createStartX = x;
                    createStartY = y;
                    selected = -1;
                    return true;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mode == Mode.CREATE) {
                    ensureCreating(w, h, x, y);
                    invalidate();
                    return true;
                }
                if (selected < 0 || selected >= regions.size() || mode == Mode.NONE) {
                    return true;
                }
                float dx = (x - lastX) / w;
                float dy = (y - lastY) / h;
                lastX = x;
                lastY = y;
                OcrRegion r = regions.get(selected);
                applyDrag(r, mode, dx, dy);
                r.normalize();
                enforceMinPx(r, w, h);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mode == Mode.CREATE) {
                    ensureCreating(w, h, x, y);
                }
                if (mode != Mode.NONE) {
                    notifyChanged();
                }
                mode = Mode.NONE;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    void finishEdit() {
        if (listener != null) {
            listener.onEditFinished(getRegions());
        }
    }

    private void ensureCreating(int w, int h, float x, float y) {
        float l = Math.min(createStartX, x) / w;
        float t = Math.min(createStartY, y) / h;
        float r = Math.max(createStartX, x) / w;
        float b = Math.max(createStartY, y) / h;
        if (selected < 0 || selected >= regions.size()) {
            if (regions.size() >= OcrRegionStore.MAX_REGIONS) {
                return;
            }
            regions.add(new OcrRegion(l, t, r, b));
            selected = regions.size() - 1;
        } else {
            OcrRegion region = regions.get(selected);
            region.left = l;
            region.top = t;
            region.right = r;
            region.bottom = b;
            region.normalize();
            enforceMinPx(region, w, h);
        }
    }

    private void applyDrag(OcrRegion r, Mode mode, float dx, float dy) {
        switch (mode) {
            case MOVE:
                r.left += dx;
                r.right += dx;
                r.top += dy;
                r.bottom += dy;
                if (r.left < 0f) {
                    r.right -= r.left;
                    r.left = 0f;
                }
                if (r.top < 0f) {
                    r.bottom -= r.top;
                    r.top = 0f;
                }
                if (r.right > 1f) {
                    r.left -= (r.right - 1f);
                    r.right = 1f;
                }
                if (r.bottom > 1f) {
                    r.top -= (r.bottom - 1f);
                    r.bottom = 1f;
                }
                break;
            case RESIZE_TL:
                r.left += dx;
                r.top += dy;
                break;
            case RESIZE_TR:
                r.right += dx;
                r.top += dy;
                break;
            case RESIZE_BL:
                r.left += dx;
                r.bottom += dy;
                break;
            case RESIZE_BR:
                r.right += dx;
                r.bottom += dy;
                break;
            default:
                break;
        }
    }

    private void enforceMinPx(OcrRegion r, int w, int h) {
        float minW = MIN_PX / w;
        float minH = MIN_PX / h;
        if (r.width() < minW) {
            r.right = Math.min(1f, r.left + minW);
        }
        if (r.height() < minH) {
            r.bottom = Math.min(1f, r.top + minH);
        }
        r.normalize();
    }

    private int hitRegion(float x, float y, int w, int h) {
        float nx = x / w;
        float ny = y / h;
        for (int i = regions.size() - 1; i >= 0; i--) {
            if (regions.get(i).contains(nx, ny)) {
                return i;
            }
        }
        return -1;
    }

    private Mode hitHandle(RectF box, float x, float y) {
        if (near(x, y, box.left, box.top)) {
            return Mode.RESIZE_TL;
        }
        if (near(x, y, box.right, box.top)) {
            return Mode.RESIZE_TR;
        }
        if (near(x, y, box.left, box.bottom)) {
            return Mode.RESIZE_BL;
        }
        if (near(x, y, box.right, box.bottom)) {
            return Mode.RESIZE_BR;
        }
        return Mode.NONE;
    }

    private boolean near(float x, float y, float hx, float hy) {
        float d = HANDLE * 1.2f;
        return Math.abs(x - hx) <= d && Math.abs(y - hy) <= d;
    }

    private static void toPx(OcrRegion r, RectF out, int w, int h) {
        out.set(r.left * w, r.top * h, r.right * w, r.bottom * h);
    }

    private void notifyChanged() {
        if (listener != null) {
            listener.onRegionsChanged(getRegions());
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
