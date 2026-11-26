package com.simpleview.pdfviewer;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Continuous-scroll PDF view with pinch / double-tap zoom, built only on the framework's PdfRenderer.
 *
 * Every page is first drawn from a low-cost bitmap sized to the screen width. While zoomed in, the visible part
 * of each page is re-rendered at full resolution in the background so text stays sharp.
 */
public class PdfView extends View {

    /** A document plus the lock that serialises access to its (single-threaded) renderer. */
    private static final class Doc {
        final PdfRenderer renderer;
        volatile boolean closed;

        Doc(PdfRenderer renderer) {
            this.renderer = renderer;
        }
    }

    /** A sharp re-render of part of one page at a specific zoom level. */
    private static final class Detail {
        final Bitmap bitmap;
        final float zoom;
        final int x, y;

        Detail(Bitmap bitmap, float zoom, int x, int y) {
            this.bitmap = bitmap;
            this.zoom = zoom;
            this.x = x;
            this.y = y;
        }

        boolean covers(int x0, int y0, int x1, int y1) {
            return x0 >= x && y0 >= y && x1 <= x + bitmap.getWidth() && y1 <= y + bitmap.getHeight();
        }
    }

    private static final float MAX_ZOOM = 5f;
    private static final float DOUBLE_TAP_ZOOM = 2.5f;
    private static final float DETAIL_RATIO = 1.05f;
    private static final int MAX_BITMAP_SIDE = 4096;
    private static final float MAX_BASE_PIXELS = 2_500_000f;
    private static final long DETAIL_DELAY_MS = 120;
    private static final long INDICATOR_MS = 1500;
    private static final int PREFETCH_PAGES = 1;

    private final float density = getResources().getDisplayMetrics().density;
    private final float gap1 = 8 * density;

    private final Paint pagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final OverScroller scroller;
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaleGestures;

    private final LruCache<Integer, Bitmap> cache;
    private final HashSet<Integer> pending = new HashSet<>();
    private final HashSet<Integer> failed = new HashSet<>();
    private final HashMap<Integer, Detail> details = new HashMap<>();

    private Doc doc;
    private ExecutorService executor;

    private int pageCount;
    private int bottomInset;
    private float[] pageW = new float[0];
    private float[] pageH = new float[0];
    private float[] top1 = new float[1];
    private float zoom = 1f;
    private float offX, offY;
    private int currentPage;
    private int pendingPage = -1;
    private boolean inverted;
    private boolean scaling;
    private float lastFocusX, lastFocusY;
    private long indicatorUntil;
    private ValueAnimator zoomAnimator;

    // Bumped when cached bitmaps become invalid (new document / new width) and when zoom changes.
    private volatile int layoutGeneration;
    private volatile int detailGeneration;
    private volatile int visibleFirst, visibleLast;

    private final Runnable detailRunnable = this::requestDetails;
    private final Runnable invalidateRunnable = this::invalidate;

    public PdfView(Context context) {
        this(context, null);
    }

    public PdfView(Context context, AttributeSet attrs) {
        super(context, attrs);
        long maxBytes = Math.max(48L * 1024 * 1024, Math.min(Runtime.getRuntime().maxMemory() / 4, 128L * 1024 * 1024));
        cache = new LruCache<Integer, Bitmap>((int) (maxBytes / 1024)) {
            @Override
            protected int sizeOf(Integer key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        scroller = new OverScroller(context);
        gestures = new GestureDetector(context, new GestureListener());
        gestures.setIsLongpressEnabled(false);
        scaleGestures = new ScaleGestureDetector(context, new ScaleListener());
        scaleGestures.setQuickScaleEnabled(false);

        pillPaint.setColor(0xCC202020);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14,
                getResources().getDisplayMetrics()));
        setFocusable(true);
    }

    // ---------------------------------------------------------------- public API

    /** Takes ownership of {@code renderer}; page sizes are in PDF points. */
    public void setDocument(PdfRenderer renderer, float[] widths, float[] heights, int startPage) {
        close();
        doc = new Doc(renderer);
        executor = Executors.newSingleThreadExecutor();
        pageCount = widths.length;
        pageW = widths;
        pageH = heights;
        top1 = new float[pageCount + 1];
        zoom = 1f;
        offX = 0;
        offY = 0;
        currentPage = 0;
        pendingPage = startPage;
        layoutGeneration++;
        detailGeneration++;
        computeLayout();
        applyPendingPage();
        invalidate();
    }

    /** Releases the current document. Safe to call repeatedly. */
    public void close() {
        layoutGeneration++;
        detailGeneration++;
        removeCallbacks(detailRunnable);
        removeCallbacks(invalidateRunnable);
        if (zoomAnimator != null) {
            zoomAnimator.cancel();
        }
        scroller.forceFinished(true);
        cache.evictAll();
        pending.clear();
        failed.clear();
        clearDetails();
        pageCount = 0;
        pendingPage = -1;

        final Doc old = doc;
        ExecutorService oldExecutor = executor;
        doc = null;
        executor = null;
        if (old != null) {
            old.closed = true;
            // Close on the render thread so we never block the UI while a page is being rendered.
            oldExecutor.execute(() -> {
                synchronized (old) {
                    old.renderer.close();
                }
            });
            oldExecutor.shutdown();
        }
        invalidate();
    }

    public int getPageCount() {
        return pageCount;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public void goToPage(int page) {
        if (pageCount == 0) {
            return;
        }
        page = Math.max(0, Math.min(pageCount - 1, page));
        scroller.forceFinished(true);
        moveTo(offX, (top1[page] - gap1) * zoom);
        setCurrentPage(page);
    }

    /** Space at the bottom covered by system bars; pages can scroll above it but are drawn beneath it. */
    public void setBottomInset(int inset) {
        if (inset != bottomInset) {
            bottomInset = inset;
            clampOffsets();
            invalidate();
        }
    }

    public void setInverted(boolean inverted) {
        this.inverted = inverted;
        if (inverted) {
            ColorMatrix negative = new ColorMatrix(new float[] {
                    -1, 0, 0, 0, 255,
                    0, -1, 0, 0, 255,
                    0, 0, -1, 0, 255,
                    0, 0, 0, 1, 0});
            pagePaint.setColorFilter(new ColorMatrixColorFilter(negative));
        } else {
            pagePaint.setColorFilter(null);
        }
        invalidate();
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (pageCount > 0 && oldw > 0 && w != oldw) {
            // Keep the same spot in the document when the width changes (rotation, split screen).
            int anchor = pageAt(offY / zoom);
            float fraction = (offY / zoom - top1[anchor]) / Math.max(1f, pageH[anchor] * fit(anchor));
            float xRatio = (float) w / oldw;
            layoutGeneration++;
            detailGeneration++;
            cache.evictAll();
            pending.clear();
            failed.clear();
            clearDetails();
            computeLayout();
            offX *= xRatio;
            offY = (top1[anchor] + fraction * pageH[anchor] * fit(anchor)) * zoom;
        } else {
            computeLayout();
        }
        clampOffsets();
        applyPendingPage();
        updateCurrentPage();
        scheduleDetails();
    }

    /** Every page is scaled to fill the view width at zoom 1; returns pixels per PDF point. */
    private float fit(int page) {
        return getWidth() / pageW[page];
    }

    private void computeLayout() {
        if (pageCount == 0 || getWidth() == 0) {
            return;
        }
        float y = gap1;
        for (int i = 0; i < pageCount; i++) {
            top1[i] = y;
            y += pageH[i] * fit(i) + gap1;
        }
        top1[pageCount] = y;
    }

    private void applyPendingPage() {
        if (pendingPage >= 0 && pageCount > 0 && getWidth() > 0) {
            int page = pendingPage;
            pendingPage = -1;
            goToPage(page);
        }
    }

    private int pageAt(float y1) {
        int lo = 0;
        int hi = pageCount - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (top1[mid] <= y1) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    private float maxOffX() {
        return Math.max(0f, getWidth() * zoom - getWidth());
    }

    private float maxOffY() {
        return Math.max(0f, top1[pageCount] * zoom + bottomInset - getHeight());
    }

    private void clampOffsets() {
        offX = Math.max(0f, Math.min(maxOffX(), offX));
        offY = Math.max(0f, Math.min(maxOffY(), offY));
    }

    // ---------------------------------------------------------------- scrolling & zooming

    private void moveTo(float x, float y) {
        float oldX = offX;
        float oldY = offY;
        offX = x;
        offY = y;
        clampOffsets();
        if (offX != oldX || offY != oldY) {
            updateCurrentPage();
            showIndicator();
            scheduleDetails();
            invalidate();
        }
    }

    private void setZoom(float newZoom, float focusX, float focusY) {
        newZoom = Math.max(1f, Math.min(MAX_ZOOM, newZoom));
        if (newZoom == zoom || pageCount == 0) {
            return;
        }
        float factor = newZoom / zoom;
        float contentX = offX + focusX;
        float contentY = offY + focusY;
        zoom = newZoom;
        detailGeneration++;
        clearDetails();
        offX = contentX * factor - focusX;
        offY = contentY * factor - focusY;
        clampOffsets();
        updateCurrentPage();
        showIndicator();
        scheduleDetails();
        invalidate();
    }

    private void animateZoomTo(float target, final float focusX, final float focusY) {
        if (zoomAnimator != null) {
            zoomAnimator.cancel();
        }
        zoomAnimator = ValueAnimator.ofFloat(zoom, target);
        zoomAnimator.setDuration(200);
        zoomAnimator.addUpdateListener(a -> setZoom((Float) a.getAnimatedValue(), focusX, focusY));
        zoomAnimator.start();
    }

    private void updateCurrentPage() {
        if (pageCount == 0) {
            return;
        }
        int page;
        if (offY <= 0f) {
            page = 0;
        } else if (offY >= maxOffY() - 1f) {
            page = pageCount - 1;
        } else {
            page = pageAt((offY + getHeight() / 2f) / zoom);
        }
        setCurrentPage(page);
    }

    private void setCurrentPage(int page) {
        currentPage = page;
    }

    private void showIndicator() {
        if (pageCount < 2) {
            return;
        }
        indicatorUntil = SystemClock.uptimeMillis() + INDICATOR_MS;
        removeCallbacks(invalidateRunnable);
        postDelayed(invalidateRunnable, INDICATOR_MS + 50);
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            moveTo(scroller.getCurrX(), scroller.getCurrY());
            postInvalidateOnAnimation();
        }
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(inverted ? 0xFF1A1A1A : 0xFFD8D8D8);
        if (pageCount == 0 || getWidth() == 0) {
            return;
        }
        final float z = zoom;
        final int first = pageAt(offY / z);
        final int last = pageAt((offY + getHeight()) / z);
        visibleFirst = first;
        visibleLast = last;

        pagePaint.setColor(Color.WHITE);
        for (int i = first; i <= last; i++) {
            float w = pageW[i] * fit(i) * z;
            float h = pageH[i] * fit(i) * z;
            float left = (getWidth() * z - w) / 2f - offX;
            float top = top1[i] * z - offY;
            dst.set(left, top, left + w, top + h);

            Bitmap bitmap = cache.get(i);
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null, dst, pagePaint);
            } else {
                canvas.drawRect(dst, pagePaint);
                requestBase(i, false);
            }

            Detail detail = details.get(i);
            if (detail != null && Math.abs(detail.zoom - z) < 0.001f) {
                canvas.drawBitmap(detail.bitmap, left + detail.x, top + detail.y, pagePaint);
            }
        }
        for (int i = 1; i <= PREFETCH_PAGES; i++) {
            requestBase(last + i, true);
            requestBase(first - i, true);
        }

        if (SystemClock.uptimeMillis() < indicatorUntil && pageCount > 1) {
            drawIndicator(canvas);
        }
    }

    private void drawIndicator(Canvas canvas) {
        String text = getContext().getString(R.string.page_indicator, currentPage + 1, pageCount);
        float padH = 14 * density;
        float height = 32 * density;
        float width = textPaint.measureText(text) + 2 * padH;
        float cx = getWidth() / 2f;
        float bottom = getHeight() - bottomInset - 20 * density;
        dst.set(cx - width / 2f, bottom - height, cx + width / 2f, bottom);
        canvas.drawRoundRect(dst, height / 2f, height / 2f, pillPaint);
        float baseline = dst.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f;
        canvas.drawText(text, cx, baseline, textPaint);
    }

    // ---------------------------------------------------------------- background rendering

    /** Fraction of a page's zoom-1 pixel size at which its cached base bitmap is rendered. */
    private float baseFactor(int page) {
        float w = pageW[page] * fit(page);
        float h = pageH[page] * fit(page);
        float factor = 1f;
        float longest = Math.max(w, h);
        if (longest > MAX_BITMAP_SIDE) {
            factor = MAX_BITMAP_SIDE / longest;
        }
        float pixels = w * h * factor * factor;
        if (pixels > MAX_BASE_PIXELS) {
            factor *= (float) Math.sqrt(MAX_BASE_PIXELS / pixels);
        }
        return factor;
    }

    private void requestBase(int index, boolean prefetch) {
        final ExecutorService ex = executor;
        final Doc d = doc;
        if (ex == null || index < 0 || index >= pageCount || cache.get(index) != null
                || failed.contains(index) || pending.contains(index)) {
            return;
        }
        final float factor = baseFactor(index);
        final int bw = Math.max(1, Math.round(pageW[index] * fit(index) * factor));
        final int bh = Math.max(1, Math.round(pageH[index] * fit(index) * factor));
        // Never let speculative pre-rendering push on-screen pages out of the cache.
        if (prefetch && cache.size() + bw * bh * 4 / 1024 > cache.maxSize() * 3 / 4) {
            return;
        }
        pending.add(index);
        final int generation = layoutGeneration;
        ex.execute(() -> {
            Bitmap bitmap = null;
            boolean wanted = generation == layoutGeneration
                    && index >= visibleFirst - PREFETCH_PAGES - 1 && index <= visibleLast + PREFETCH_PAGES + 1;
            if (wanted) {
                bitmap = render(d, index, bw, bh, null);
            }
            final Bitmap result = bitmap;
            final boolean gaveUp = wanted && bitmap == null;
            post(() -> {
                if (generation != layoutGeneration) {
                    return;
                }
                pending.remove(index);
                if (result != null) {
                    cache.put(index, result);
                } else if (gaveUp) {
                    failed.add(index);
                    return;
                }
                invalidate();
            });
        });
    }

    private void scheduleDetails() {
        removeCallbacks(detailRunnable);
        postDelayed(detailRunnable, DETAIL_DELAY_MS);
    }

    private void clearDetails() {
        for (Detail detail : details.values()) {
            detail.bitmap.recycle();
        }
        details.clear();
    }

    /** Re-renders the visible part of each visible page at the current zoom for crisp text. */
    private void requestDetails() {
        final ExecutorService ex = executor;
        final Doc d = doc;
        if (ex == null || pageCount == 0 || getWidth() == 0) {
            return;
        }
        final float z = zoom;
        final int generation = detailGeneration;
        final int layoutGen = layoutGeneration;
        final int first = pageAt(offY / z);
        final int last = pageAt((offY + getHeight()) / z);
        final float marginX = getWidth() * 0.25f;
        final float marginY = getHeight() * 0.25f;

        for (int i = first; i <= last; i++) {
            if (z <= baseFactor(i) * DETAIL_RATIO) {
                continue; // The base bitmap is already at least as sharp as what is on screen.
            }
            final int index = i;
            float w = pageW[i] * fit(i) * z;
            float h = pageH[i] * fit(i) * z;
            float left = (getWidth() * z - w) / 2f - offX;
            float top = top1[i] * z - offY;

            // Visible region in page-local pixels, without and with a margin for small follow-up scrolls.
            int vx0 = (int) Math.max(0f, -left);
            int vy0 = (int) Math.max(0f, -top);
            int vx1 = (int) Math.min(w, getWidth() - left);
            int vy1 = (int) Math.min(h, getHeight() - top);
            if (vx1 <= vx0 || vy1 <= vy0) {
                continue;
            }
            Detail existing = details.get(i);
            if (existing != null && Math.abs(existing.zoom - z) < 0.001f && existing.covers(vx0, vy0, vx1, vy1)) {
                continue;
            }
            final int x0 = (int) Math.max(0f, -left - marginX);
            final int y0 = (int) Math.max(0f, -top - marginY);
            final int x1 = (int) Math.min(w, getWidth() - left + marginX);
            final int y1 = (int) Math.min(h, getHeight() - top + marginY);
            final float scale = fit(i) * z;
            final Matrix matrix = new Matrix();
            matrix.setScale(scale, scale);
            matrix.postTranslate(-x0, -y0);

            ex.execute(() -> {
                Bitmap bitmap = null;
                if (generation == detailGeneration && index >= visibleFirst - 1 && index <= visibleLast + 1) {
                    bitmap = render(d, index, x1 - x0, y1 - y0, matrix);
                }
                final Bitmap result = bitmap;
                post(() -> {
                    if (result == null) {
                        return;
                    }
                    if (generation != detailGeneration || layoutGen != layoutGeneration) {
                        result.recycle();
                        return;
                    }
                    Detail old = details.put(index, new Detail(result, z, x0, y0));
                    if (old != null) {
                        old.bitmap.recycle();
                    }
                    invalidate();
                });
            });
        }
    }

    /** Runs on the render thread. Returns null if the document was closed or rendering failed. */
    private Bitmap render(Doc d, int index, int width, int height, Matrix transform) {
        Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            post(cache::evictAll);
            return null;
        }
        bitmap.eraseColor(Color.WHITE);
        synchronized (d) {
            if (d.closed) {
                return null;
            }
            try (PdfRenderer.Page page = d.renderer.openPage(index)) {
                page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            } catch (RuntimeException e) {
                return null;
            }
        }
        return bitmap;
    }

    // ---------------------------------------------------------------- input

    // The view only pans and zooms; it has no click action for accessibility services to trigger.
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleGestures.onTouchEvent(event);
        gestures.onTouchEvent(event);
        return true;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_SCROLL && pageCount > 0) {
            float dy = -event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 64 * density;
            float dx = -event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 64 * density;
            scroller.forceFinished(true);
            moveTo(offX + dx, offY + dy);
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    private final class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            // The zoom animation is deliberately not cancelled here: onDown follows onDoubleTap for the same touch.
            scroller.forceFinished(true);
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            if (!scaling) {
                if (zoomAnimator != null) {
                    zoomAnimator.cancel();
                }
                moveTo(offX + dx, offY + dy);
            }
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
            if (scaling || pageCount == 0) {
                return false;
            }
            scroller.fling(Math.round(offX), Math.round(offY), Math.round(-vx), Math.round(-vy),
                    0, Math.round(maxOffX()), 0, Math.round(maxOffY()));
            postInvalidateOnAnimation();
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            animateZoomTo(zoom > 1.1f ? 1f : DOUBLE_TAP_ZOOM, e.getX(), e.getY());
            return true;
        }
    }

    private final class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(ScaleGestureDetector d) {
            scaling = true;
            lastFocusX = d.getFocusX();
            lastFocusY = d.getFocusY();
            scroller.forceFinished(true);
            if (zoomAnimator != null) {
                zoomAnimator.cancel();
            }
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector d) {
            setZoom(zoom * d.getScaleFactor(), d.getFocusX(), d.getFocusY());
            // Two-finger pan while pinching.
            moveTo(offX - (d.getFocusX() - lastFocusX), offY - (d.getFocusY() - lastFocusY));
            lastFocusX = d.getFocusX();
            lastFocusY = d.getFocusY();
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector d) {
            scaling = false;
            scheduleDetails();
        }
    }
}
