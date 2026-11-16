package com.recom3.maps;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.location.Location;
import android.view.MotionEvent;
import android.view.View;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class MapHud extends View {
    private static final int PANEL = Color.rgb(16, 33, 45);
    private static final int PANEL_LIGHT = Color.rgb(42, 61, 72);
    private static final int MAP_EMPTY = Color.rgb(198, 210, 214);
    private static final int TEXT = Color.rgb(249, 252, 254);
    private static final int MUTED_TEXT = Color.rgb(193, 216, 230);
    private static final int BLUE = Color.rgb(65, 198, 255);
    private static final int WARNING = Color.rgb(255, 212, 135);
    private static final float TOP = 31;
    private static final float FOOT = 36;
    private static final float BUTTON_WIDTH = 44;

    private final MapsActivity activity;
    private final ViewportTiles tiles;
    private final HudPosition position;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rectangle = new RectF();
    private final Path arrow = new Path();
    private List<Viewport.Placement> placements = Collections.emptyList();
    private float scale = 1;
    private float width = 428;
    private float height = 240;
    private float pressX;
    private float pressY;
    private float lastX;
    private float lastY;
    private boolean dragging;
    private int renderedZoom = -1;
    private double renderedX;
    private double renderedY;

    MapHud(MapsActivity activity, ViewportTiles tiles, HudPosition position) {
        super(activity);
        this.activity = activity;
        this.tiles = tiles;
        this.position = position;
        // Keep D-pad events at the Activity so they never move focus among HUD controls.
        setFocusable(false);
        setContentDescription("Map. Direction keys pan. Enter switches between pan and zoom.");
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (w == 0 || h == 0) return;
        scale = Math.min(w / 428f, h / 240f);
        width = w / scale;
        height = h / scale;
        renderedZoom = -1;
        updateMap();
    }

    void updateMap() {
        Viewport map = activity.viewport;
        if (map.zoom != renderedZoom || map.centerX != renderedX || map.centerY != renderedY) {
            renderedZoom = map.zoom;
            renderedX = map.centerX;
            renderedY = map.centerY;
            placements = map.placements(width, mapHeight());
            tiles.show(map.requestOrder(placements, width, mapHeight()));
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.scale(scale, scale);
        canvas.drawColor(MAP_EMPTY);
        canvas.save();
        canvas.clipRect(0, TOP, width, height - FOOT);
        canvas.translate(0, TOP);
        for (Viewport.Placement placement : placements) {
            Bitmap bitmap = tiles.image(placement.tile);
            if (bitmap == null) continue;
            float x = Math.round(placement.x);
            float y = Math.round(placement.y);
            rectangle.set(x, y, x + Viewport.TILE_SIZE, y + Viewport.TILE_SIZE);
            paint.setColor(Color.WHITE);
            canvas.drawBitmap(bitmap, null, rectangle, paint);
        }
        drawPosition(canvas);
        canvas.restore();

        fill(canvas, 0, 0, width, TOP, PANEL);
        String network = activity.networkLabel();
        text(canvas, network, 10, 21, 15, network.equals("Offline") ? WARNING : BLUE, true);
        text(canvas, position.gpsLabel(), width / 2 - 40, 21, 14, TEXT, true);
        float bearing = position.heading();
        String heading = Float.isNaN(bearing) ? "Heading —"
                : String.format(Locale.US, "%03d°%s", Math.round(bearing) % 360,
                        position.magneticOnly() ? " M" : " T");
        rightText(canvas, heading, width - 10, 21, 14, MUTED_TEXT);

        String badge = "Z" + activity.viewport.zoom + (activity.followsGps() ? "  FOLLOW" : "");
        chip(canvas, 8, TOP + 7, badge, 13, TEXT);

        String message = activity.tileMessage();
        if (message != null) chip(canvas, 8, height - FOOT - 32, message, 14, WARNING);

        fill(canvas, 0, height - FOOT, width, height, PANEL);
        button(canvas, 7, "−", 20);
        button(canvas, 7 + BUTTON_WIDTH, "+", 20);
        button(canvas, 7 + 2 * BUTTON_WIDTH, "GPS", 13);
        text(canvas, activity.isZoomMode() ? "ZOOM" : "PAN", 149, height - 12, 13, BLUE, true);
        rightText(canvas, BuildConfig.TILE_CREDIT, width - 8, height - 12, 13, TEXT);
    }

    private void drawPosition(Canvas canvas) {
        Location fix = position.fix();
        if (fix == null) return;
        float x = activity.viewport.screenX(fix.getLongitude(), width);
        float y = activity.viewport.screenY(fix.getLatitude(), mapHeight());
        paint.setColor(Color.WHITE);
        canvas.drawCircle(x, y, 8, paint);
        paint.setColor(Color.rgb(0, 116, 200));
        canvas.drawCircle(x, y, 5.5f, paint);
        float heading = position.heading();
        if (Float.isNaN(heading)) return;
        canvas.save();
        canvas.rotate(heading, x, y);
        arrow.reset();
        arrow.moveTo(x, y - 20);
        arrow.lineTo(x - 7, y - 10);
        arrow.lineTo(x + 7, y - 10);
        arrow.close();
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL_AND_STROKE);
        paint.setStrokeWidth(2);
        canvas.drawPath(arrow, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(0, 100, 171));
        canvas.drawPath(arrow, paint);
        canvas.restore();
    }

    private void fill(Canvas canvas, float x, float y, float right, float bottom, int color) {
        paint.setColor(color);
        canvas.drawRect(x, y, right, bottom, paint);
    }

    private void chip(Canvas canvas, float x, float y, String text, int size, int color) {
        paint.setTextSize(size);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        fill(canvas, x, y, x + paint.measureText(text) + 16, y + 24, PANEL);
        text(canvas, text, x + 8, y + 17, size, color, true);
    }

    private void button(Canvas canvas, float x, String label, int size) {
        float bottom = height - 5;
        fill(canvas, x, height - FOOT + 5, x + BUTTON_WIDTH - 4, bottom, PANEL_LIGHT);
        paint.setTextSize(size);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        float left = x + (BUTTON_WIDTH - 4 - paint.measureText(label)) / 2;
        text(canvas, label, left, height - (size == 20 ? 10 : 13), size, TEXT, true);
    }

    private void rightText(Canvas canvas, String label, float right, float y, int size, int color) {
        paint.setTextSize(size);
        paint.setTypeface(Typeface.DEFAULT);
        text(canvas, label, right - paint.measureText(label), y, size, color, false);
    }

    private void text(Canvas canvas, String label, float x, float y, int size, int color, boolean bold) {
        paint.setColor(color);
        paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        paint.setTextSize(size);
        canvas.drawText(label, x, y, paint);
    }

    private float mapHeight() {
        return height - TOP - FOOT;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX() / scale;
        float y = event.getY() / scale;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressX = lastX = x;
                pressY = lastY = y;
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pressY >= TOP && pressY < height - FOOT
                        && (dragging || Math.abs(x - pressX) + Math.abs(y - pressY) > 6)) {
                    dragging = true;
                    activity.pan(lastX - x, lastY - y);
                    lastX = x;
                    lastY = y;
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging && pressY >= height - FOOT && y >= height - FOOT) {
                    if (x < 7 + BUTTON_WIDTH) activity.zoom(-1);
                    else if (x < 7 + 2 * BUTTON_WIDTH) activity.zoom(1);
                    else if (x < 7 + 3 * BUTTON_WIDTH) activity.recenter();
                    else if (x >= 208) activity.openCredits();
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
