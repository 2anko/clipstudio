package videocutter.view.controls;

import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

public class RangeTrimBar extends Pane {
    private final Rectangle track = new Rectangle();
    private final Rectangle left = new Rectangle(8, 24);
    private final Rectangle right = new Rectangle(8, 24);
    private final Rectangle fill = new Rectangle();

    private long min = 0, max = 10_000;
    private final LongProperty startMs = new SimpleLongProperty(0);
    private final LongProperty endMs   = new SimpleLongProperty(10_000);
    private final Rectangle playhead = new Rectangle(2, 28);  // live position indicator
    private final LongProperty playheadMs = new SimpleLongProperty(0);

    // keep at least this many ms between thumbs
    private static final long MIN_SPAN_MS = 100; // tweak if you like

    private enum Thumb { NONE, LEFT, RIGHT }
    private Thumb active = Thumb.NONE;

    // maintains cursor lock to thumb center while dragging
    private double dragOffsetPx = 0;

    public interface ChangeHandler { void onChange(long startMs, long endMs); }
    private ChangeHandler handler;

    public RangeTrimBar() {
        setPrefHeight(44);
        setPadding(new Insets(8));

        track.setHeight(6);
        track.setArcWidth(6);
        track.setArcHeight(6);
        track.setFill(Color.web("#3f4566"));

        fill.setHeight(6);
        fill.setFill(Color.web("#6f87ff"));

        left.setFill(Color.web("#9aa6ff"));
        right.setFill(Color.web("#9aa6ff"));
        left.setCursor(Cursor.H_RESIZE);
        right.setCursor(Cursor.H_RESIZE);

        getChildren().addAll(track, fill, left, right);

        // --- Thumb press: set active + lock offset so the thumb sticks to cursor ---
        left.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            active = Thumb.LEFT;
            dragOffsetPx = thumbCenterX(left) - toLocalX(e);
            e.consume();
        });
        right.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            active = Thumb.RIGHT;
            dragOffsetPx = thumbCenterX(right) - toLocalX(e);
            e.consume();
        });

        // --- Drag anywhere on the bar while a thumb is active ---
        addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            if (active == Thumb.NONE) return;
            double x = clamp(toLocalX(e) + dragOffsetPx, padLeft(), getWidth() - padRight());
            long v = pixelToValue(x);

            if (active == Thumb.LEFT) {
                long newStart = clamp(v, min, endMs.get() - MIN_SPAN_MS);
                if (newStart != startMs.get()) startMs.set(newStart);
            } else {
                long newEnd = clamp(v, startMs.get() + MIN_SPAN_MS, max);
                if (newEnd != endMs.get()) endMs.set(newEnd);
            }
            requestLayout();
            if (handler != null) handler.onChange(startMs.get(), endMs.get());
            e.consume();
        });

        // --- Release ---
        addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
            active = Thumb.NONE;
            e.consume();
        });

        // --- Click track to move nearest thumb to cursor ---
        track.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            long v = pixelToValue(clamp(toLocalX(e), padLeft(), getWidth() - padRight()));
            long distL = Math.abs(v - startMs.get());
            long distR = Math.abs(v - endMs.get());
            if (distL <= distR) {
                startMs.set(clamp(v, min, endMs.get() - MIN_SPAN_MS));
                active = Thumb.LEFT;
            } else {
                endMs.set(clamp(v, startMs.get() + MIN_SPAN_MS, max));
                active = Thumb.RIGHT;
            }
            requestLayout();
            if (handler != null) handler.onChange(startMs.get(), endMs.get());
            e.consume();
        });

        addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() == MouseButton.SECONDARY && active == Thumb.NONE) {
                double x = clamp(toLocalX(e), padLeft(), getWidth() - padRight());
                long v = pixelToValue(x);
                setPlayhead(v);                   // move the black playhead
                if (seekHandler != null) seekHandler.onSeek(v); // tell listeners to seek
                e.consume();
            }
        });

        playhead.setFill(Color.web("#000000CC")); // CC ≈ 80% opacity
        playhead.setMouseTransparent(true);         // don’t steal drag events
        // make sure z-order puts playhead above the fill but below/above thumbs as you prefer:
        getChildren().setAll(track, fill, playhead, left, right);
    }

    // ---------------- API ----------------
    public void setBounds(long min, long max) {
        this.min = min;
        this.max = Math.max(min + MIN_SPAN_MS, max);
        if (startMs.get() < min) startMs.set(min);
        if (endMs.get()   > max) endMs.set(max);
        if (endMs.get() - startMs.get() < MIN_SPAN_MS) {
            endMs.set(Math.min(max, startMs.get() + MIN_SPAN_MS));
        }
        requestLayout();
    }

    public void setPlayhead(long ms) {
        long clamped = clamp(ms, min, max);
        if (clamped != playheadMs.get()) {
            playheadMs.set(clamped);
            layoutPlayheadOnly();   // fast path; no full relayout needed
        }
    }
    public long getPlayhead() { return playheadMs.get(); }

    public void setValues(long start, long end) {
        startMs.set(clamp(start, min, max - MIN_SPAN_MS));
        endMs.set(clamp(end, startMs.get() + MIN_SPAN_MS, max));
        requestLayout();
    }

    public long getStartMs() { return startMs.get(); }
    public long getEndMs()   { return endMs.get(); }

    public void setOnChanged(ChangeHandler h) { this.handler = h; }

    public interface SeekHandler { void onSeek(long ms); }
    private SeekHandler seekHandler;
    public void setOnSeek(SeekHandler h) { this.seekHandler = h; }

    // ---------------- Layout ----------------
    @Override protected void layoutChildren() {
        double w = getWidth() - padLeft() - padRight();
        double x0 = padLeft();
        double y  = getPadding().getTop() + 12;

        track.setLayoutX(x0);
        track.setLayoutY(y);
        track.setWidth(w);

        double lx = valueToPixel(startMs.get());
        double rx = valueToPixel(endMs.get());

        left.setLayoutX(lx - left.getWidth()/2);
        left.setLayoutY(y - 9);

        right.setLayoutX(rx - right.getWidth()/2);
        right.setLayoutY(y - 9);

        fill.setLayoutX(lx);
        fill.setLayoutY(y);
        fill.setWidth(Math.max(2, rx - lx));

        // ⬇️ playhead placement
        double px = valueToPixel(playheadMs.get());
        playhead.setLayoutX(px - playhead.getWidth()/2);
        playhead.setLayoutY(y - 11);
    }

    // ---------------- Helpers ----------------
    private double toLocalX(MouseEvent e) {
        Point2D p = sceneToLocal(e.getSceneX(), e.getSceneY());
        return p.getX();
    }

    private double padLeft()  { return getPadding().getLeft(); }
    private double padRight() { return getPadding().getRight(); }

    private long pixelToValue(double x) {
        double w = Math.max(1, getWidth() - padLeft() - padRight());
        double p = (x - padLeft()) / w;
        p = clamp(p, 0.0, 1.0);
        return (long) (min + p * (max - min));
    }

    private double valueToPixel(long v) {
        double w = Math.max(1, getWidth() - padLeft() - padRight());
        double p = (v - min) / (double) (max - min);
        return padLeft() + p * w;
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static long clamp(long v, long lo, long hi) { return Math.max(lo, Math.min(hi, v)); }

    private double thumbCenterX(Rectangle r) { return r.getLayoutX() + r.getWidth()/2; }

    private void layoutPlayheadOnly() {
        double y = getPadding().getTop() + 12;
        double px = valueToPixel(playheadMs.get());
        playhead.setLayoutX(px - playhead.getWidth()/2);
        playhead.setLayoutY(y - 11);
    }
}
