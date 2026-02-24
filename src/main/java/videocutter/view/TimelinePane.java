package videocutter.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import org.kordamp.ikonli.javafx.FontIcon;
import videocutter.model.TimelineClip;

import java.util.ArrayList;
import java.util.List;

public class TimelinePane {
    private final BorderPane root = new BorderPane();

    private final Button cutBtn    = iconBtn("fas-cut");
    private final Button deleteBtn = iconBtn("fas-trash-alt");
    private final Button linkBtn   = iconBtn("fas-link");

    private final Label  zoomLabel = new Label("100%");
    private final Button zoomOut   = iconBtn("fas-search-minus");
    private final Button zoomIn    = iconBtn("fas-search-plus");

    private final VBox trackLabels = new VBox(10);

    private final VBox content = new VBox(0);
    private final Pane ruler   = new Pane();
    private final Pane lanes   = new Pane();

    private final ScrollPane scroller = new ScrollPane(content);

    private static final double BASE_PX_PER_SECOND = 20.0;
    private static final int    RULER_STEP_SECONDS  = 5;
    private double pxPerSecond = BASE_PX_PER_SECOND;

    private static final double X0     = 10;
    private static final double GAP_PX = 2;

    private final Line    laneSep      = new Line(0, 70, 5000, 70);
    private final Polygon playheadTri  = new Polygon(0, 0, 10, 0, 5, 7);
    private final Line    playheadLine = new Line(0, 0, 0, 140);

    private long playheadTimelineMs = 0;

    private final List<ClipNode> clipNodes  = new ArrayList<>();
    private List<TimelineClip>   lastClips  = List.of();
    private TimelineClip         selectedClip = null;

    public TimelinePane() {
        root.getStyleClass().addAll("panel", "timeline");
        root.setPadding(new Insets(10, 12, 12, 12));

        for (Button b : new Button[]{cutBtn, deleteBtn, linkBtn, zoomOut, zoomIn}) {
            b.getStyleClass().addAll("btn", "icon-btn");
            b.setFocusTraversable(false);
        }
        zoomLabel.getStyleClass().add("muted");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox tools = new HBox(8, cutBtn, deleteBtn, linkBtn, spacer, zoomOut, zoomLabel, zoomIn);
        tools.setAlignment(Pos.CENTER_LEFT);
        tools.setPadding(new Insets(0, 0, 10, 0));
        root.setTop(tools);

        Label v   = new Label("▣  Video");
        Label a   = new Label("♫  Audio");
        Label add = new Label("+  Add track");
        v.getStyleClass().add("track-label");
        a.getStyleClass().add("track-label");
        add.getStyleClass().addAll("track-label", "muted");

        trackLabels.getChildren().addAll(v, a, add);
        trackLabels.setPadding(new Insets(30, 10, 0, 0));
        trackLabels.setMinWidth(110);
        root.setLeft(trackLabels);

        ruler.getStyleClass().add("timeline-ruler");
        ruler.setPrefHeight(26);

        lanes.getStyleClass().add("timeline-lanes");
        lanes.setPrefHeight(140);

        laneSep.getStyleClass().add("lane-sep");
        lanes.getChildren().add(laneSep);

        playheadTri.getStyleClass().add("playhead-tri");
        playheadTri.setMouseTransparent(true);

        playheadLine.getStyleClass().add("playhead-line");
        playheadLine.setMouseTransparent(true);

        ruler.getChildren().add(playheadTri);
        lanes.getChildren().add(playheadLine);

        content.getChildren().addAll(ruler, lanes);
        content.getStyleClass().add("timeline-content");

        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroller.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setFitToHeight(true);
        scroller.getStyleClass().add("transparent-scroll");
        root.setCenter(scroller);

        // Scroll wheel -> horizontal scroll
        scroller.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (e.getDeltaY() == 0) return;
            double contentW  = content.getBoundsInLocal().getWidth();
            double viewportW = scroller.getViewportBounds().getWidth();
            double maxScrollPx = contentW - viewportW;
            if (maxScrollPx <= 0) return;
            double dx   = -e.getDeltaY();
            double next = scroller.getHvalue() + (dx / maxScrollPx);
            scroller.setHvalue(clamp01(next));
            e.consume();
        });

        // DnD onto lanes
        lanes.setOnDragOver(e -> {
            if (e.getDragboard().hasString()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        lanes.setOnDragDropped(e -> {
            String idStr = e.getDragboard().getString();
            if (idStr != null && onDrop != null) onDrop.onDrop(Long.parseLong(idStr));
            e.setDropCompleted(true);
            e.consume();
        });

        // Left-click OR right-click → select + scrub
        lanes.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> handleTimelineClick(e, e.getX()));
        ruler.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> handleTimelineClick(e, e.getX()));

        zoomOut.setOnAction(e -> {
            pxPerSecond = Math.max(8, pxPerSecond * 0.85);
            zoomLabel.setText((int) (pxPerSecond / BASE_PX_PER_SECOND * 100) + "%");
            setClips(lastClips);
        });
        zoomIn.setOnAction(e -> {
            pxPerSecond = Math.min(200, pxPerSecond * 1.15);
            zoomLabel.setText((int) (pxPerSecond / BASE_PX_PER_SECOND * 100) + "%");
            setClips(lastClips);
        });

        relayout();
        updatePlayheadVisualFromTimelineMs();
    }

    // ---- click handling ----

    private void handleTimelineClick(MouseEvent e, double x) {
        if (e.getButton() != MouseButton.PRIMARY && e.getButton() != MouseButton.SECONDARY) return;

        Hit hit = hitTestByXAndPick(x, e);
        if (hit == null) return;

        selectClip(hit.node.clip);
        setPlayheadTimelineMs(hit.timelineMs);
        if (onScrub != null) onScrub.onScrub(hit.node.clip, hit.sourceMs, hit.timelineMs);

        e.consume();
    }

    // ---- callbacks ----

    public interface DropHandler  { void onDrop(long assetId); }
    public interface ScrubHandler { void onScrub(TimelineClip clip, long sourceMs, long timelineMs); }

    private DropHandler  onDrop;
    private ScrubHandler onScrub;

    public void setOnDrop(DropHandler h)   { onDrop  = h; }
    public void setOnScrub(ScrubHandler h) { onScrub = h; }

    // ---- public API ----

    public BorderPane getRoot() { return root; }
    public Button cutBtn()      { return cutBtn; }

    public void selectClip(TimelineClip clip) {
        this.selectedClip = clip;
        for (ClipNode n : clipNodes) {
            n.getStyleClass().remove("clip-selected");
            if (n.clip == clip) n.getStyleClass().add("clip-selected");
        }
    }

    public void setPlayheadTimelineMs(long timelineMs) {
        playheadTimelineMs = Math.max(0, timelineMs);
        updatePlayheadVisualFromTimelineMs();
    }

    public long getPlayheadTimelineMs() { return playheadTimelineMs; }

    public void setClips(List<TimelineClip> clips) {
        if (clips == null) clips = List.of();
        lastClips = clips;

        lanes.getChildren().removeIf(n -> n instanceof ClipNode);
        clipNodes.clear();

        double x             = X0;
        double yVideo        = 34;
        long   accTimelineMs = 0;

        for (TimelineClip c : clips) {
            long   durMs = Math.max(0, c.endMs() - c.startMs());
            double w     = Math.max(40, (durMs / 1000.0) * pxPerSecond);

            ClipNode node = new ClipNode(c, accTimelineMs, durMs);
            node.relocate(x, yVideo);
            node.setPrefWidth(w);

            lanes.getChildren().add(node);
            clipNodes.add(node);

            accTimelineMs += durMs;
            x += w + GAP_PX;
        }

        double totalW = Math.max(900, x + 40);
        ruler.setPrefWidth(totalW);
        lanes.setPrefWidth(totalW);
        laneSep.setEndX(totalW);

        drawRuler(totalW);

        if (selectedClip != null) selectClip(selectedClip);
        updatePlayheadVisualFromTimelineMs();
    }

    // ---- ruler ----

    private void drawRuler(double totalW) {
        ruler.getChildren().clear();
        ruler.getChildren().add(playheadTri);

        int seconds = (int) Math.ceil(totalW / pxPerSecond);
        for (int s = 0; s <= seconds; s += RULER_STEP_SECONDS) {
            double x = X0 + s * pxPerSecond;
            Label t  = new Label(formatSeconds(s));
            t.getStyleClass().add("ruler-tick");
            t.relocate(x, 4);
            ruler.getChildren().add(t);
        }
    }

    private static String formatSeconds(int totalSeconds) {
        int mm = totalSeconds / 60;
        int ss = totalSeconds % 60;
        return String.format("%d:%02d", mm, ss);
    }

    private void relayout() {
        drawRuler(Math.max(900, ruler.getPrefWidth()));
    }

    // ---- playhead ----

    private void updatePlayheadVisualFromTimelineMs() {
        if (clipNodes.isEmpty()) { setPlayheadX(X0); return; }

        ClipNode target = null;
        for (ClipNode n : clipNodes) {
            if (playheadTimelineMs >= n.timelineStartMs
                    && playheadTimelineMs <= n.timelineStartMs + n.timelineDurMs) {
                target = n;
                break;
            }
        }
        if (target == null) {
            target = clipNodes.get(clipNodes.size() - 1);
            long end = target.timelineStartMs + target.timelineDurMs;
            playheadTimelineMs = Math.min(playheadTimelineMs, end);
        }

        long   offset = Math.max(0, Math.min(playheadTimelineMs - target.timelineStartMs, target.timelineDurMs));
        double w      = Math.max(1, target.getPrefWidth());
        double frac   = (target.timelineDurMs == 0) ? 0 : (offset / (double) target.timelineDurMs);
        double x      = target.getLayoutX() + frac * w;

        setPlayheadX(x);
    }

    private void setPlayheadX(double x) {
        playheadTri.setLayoutX(x - 5);
        playheadTri.setLayoutY(0);

        playheadLine.setStartX(x);
        playheadLine.setEndX(x);
        playheadLine.setStartY(0);
        playheadLine.setEndY(lanes.getPrefHeight());
    }

    // ---- hit testing ----

    private Hit hitTestByXAndPick(double x, MouseEvent e) {
        if (clipNodes.isEmpty()) return null;

        Node n = e.getPickResult().getIntersectedNode();
        while (n != null && !(n instanceof ClipNode)) n = n.getParent();
        if (n instanceof ClipNode cn) return makeHit(cn, x);

        ClipNode best     = null;
        double   bestDist = Double.MAX_VALUE;
        for (ClipNode cn : clipNodes) {
            double sx = cn.getLayoutX();
            double ex = sx + cn.getPrefWidth();
            double dist;
            if (x >= sx && x <= ex) { best = cn; break; }
            dist = (x < sx) ? (sx - x) : (x - ex);
            if (dist < bestDist) { bestDist = dist; best = cn; }
        }
        return (best == null) ? null : makeHit(best, x);
    }

    private Hit makeHit(ClipNode node, double x) {
        double sx     = node.getLayoutX();
        double w      = Math.max(1, node.getPrefWidth());
        double within = clamp(x - sx, 0, w);
        double frac   = within / w;

        long offsetMs  = (long) (frac * node.timelineDurMs);
        long timelineMs = node.timelineStartMs + offsetMs;
        long sourceMs   = clamp(node.clip.startMs() + offsetMs, node.clip.startMs(), node.clip.endMs());

        return new Hit(node, timelineMs, sourceMs);
    }

    private record Hit(ClipNode node, long timelineMs, long sourceMs) {}

    // ---- inner classes ----

    static class ClipNode extends StackPane {
        final TimelineClip clip;
        final long         timelineStartMs;
        final long         timelineDurMs;

        ClipNode(TimelineClip clip, long timelineStartMs, long timelineDurMs) {
            this.clip            = clip;
            this.timelineStartMs = timelineStartMs;
            this.timelineDurMs   = timelineDurMs;

            getStyleClass().add("timeline-clip");
            setPadding(new Insets(8, 10, 8, 10));

            Label label = new Label(clip.display());
            label.getStyleClass().add("clip-text");
            getChildren().add(label);
        }
    }

    // ---- helpers ----

    private static Button iconBtn(String icon) {
        Button b = new Button();
        b.setGraphic(new FontIcon(icon));
        return b;
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static long   clamp(long v,   long lo,   long hi)   { return Math.max(lo, Math.min(hi, v)); }
    private static double clamp01(double v)                      { return Math.max(0, Math.min(1, v)); }

    // diagnostic helpers (used in tests)
    public Object lanesChildCount() { return lanes.getChildren().size(); }
    public Object rulerChildCount() { return ruler.getChildren().size(); }
}