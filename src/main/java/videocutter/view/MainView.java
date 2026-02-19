package videocutter.view;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.concurrent.Task;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Polygon;
import javafx.util.Duration;

public class MainView {
    private final StackPane root = new StackPane();
    private final BorderPane content = new BorderPane();

    private final LibraryPane libraryPane = new LibraryPane();
    private final PreviewPane previewPane = new PreviewPane();
    private final TimelinePane timelinePane = new TimelinePane();
    private final ToolbarPane toolbarPane = new ToolbarPane();
    private final InspectorPane inspectorPane = new InspectorPane();

    private final StackPane exportOverlay = new StackPane();
    private final ProgressBar exportProgress = new ProgressBar(0);
    private final TriangleSpinner spinner = new TriangleSpinner(64);

    private Task<?> currentExportTask;
    private final EventHandler<javafx.concurrent.WorkerStateEvent> onExportDone = e -> hideExportOverlay();

    public MainView() {
        root.getStyleClass().addAll("app-root", "editor-root");

        SplitPane center = new SplitPane();
        center.setOrientation(Orientation.HORIZONTAL);
        center.getItems().addAll(libraryPane.getRoot(), previewPane.getRoot(), inspectorPane.getRoot());
        center.setDividerPositions(0.22, 0.78);
        BorderPane.setMargin(center, new Insets(0, 0, 0, 0));

        content.setTop(toolbarPane.getRoot());
        content.setCenter(center);
        content.setBottom(timelinePane.getRoot());

        buildExportOverlay();

        root.getChildren().addAll(content, exportOverlay);
    }

    private void buildExportOverlay() {
        exportOverlay.getStyleClass().add("export-overlay");
        exportOverlay.setVisible(false);
        exportOverlay.setManaged(false);
        exportOverlay.setPickOnBounds(true);

        exportProgress.getStyleClass().add("export-progress");
        exportProgress.setPrefWidth(320);
        exportProgress.setMaxWidth(360);

        VBox box = new VBox(14);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("export-overlay-box");
        box.getChildren().addAll(spinner, exportProgress);

        exportOverlay.getChildren().add(box);
    }

    public void showExportOverlay(Task<?> task) {
        if (task == null) {
            return;
        }

        // Clean up previous task bindings
        if (currentExportTask != null) {
            exportProgress.progressProperty().unbind();
            currentExportTask.removeEventHandler(javafx.concurrent.WorkerStateEvent.WORKER_STATE_SUCCEEDED, onExportDone);
            currentExportTask.removeEventHandler(javafx.concurrent.WorkerStateEvent.WORKER_STATE_FAILED, onExportDone);
            currentExportTask.removeEventHandler(javafx.concurrent.WorkerStateEvent.WORKER_STATE_CANCELLED, onExportDone);
        }

        currentExportTask = task;
        exportProgress.progressProperty().bind(task.progressProperty());

        exportOverlay.setVisible(true);
        exportOverlay.setManaged(true);
        spinner.start();

        task.addEventHandler(javafx.concurrent.WorkerStateEvent.WORKER_STATE_SUCCEEDED, onExportDone);
        task.addEventHandler(javafx.concurrent.WorkerStateEvent.WORKER_STATE_FAILED, onExportDone);
        task.addEventHandler(javafx.concurrent.WorkerStateEvent.WORKER_STATE_CANCELLED, onExportDone);
    }

    public void hideExportOverlay() {
        if (currentExportTask != null) {
            exportProgress.progressProperty().unbind();
            currentExportTask = null;
        }
        exportProgress.setProgress(0);
        spinner.stop();
        exportOverlay.setVisible(false);
        exportOverlay.setManaged(false);
    }

    public StackPane getRoot() {
        return root;
    }

    public LibraryPane library() {
        return libraryPane;
    }

    public PreviewPane preview() {
        return previewPane;
    }

    public TimelinePane timeline() {
        return timelinePane;
    }

    public ToolbarPane toolbar() {
        return toolbarPane;
    }

    public InspectorPane inspector() {
        return inspectorPane;
    }

    private static class TriangleSpinner extends StackPane {
        private final Polygon tri;
        private final Timeline anim;

        TriangleSpinner(double size) {
            setMinSize(size, size);
            setPrefSize(size, size);
            setMaxSize(size, size);

            double r = size * 0.36;
            tri = new Polygon(
                    0.0, -r,
                    r * 0.866, r * 0.5,
                    -r * 0.866, r * 0.5
            );
            tri.getStyleClass().add("export-spinner");
            tri.setFill(null);

            tri.getStrokeDashArray().setAll(24.0, 88.0);

            anim = new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(tri.strokeDashOffsetProperty(), 0.0)),
                    new KeyFrame(Duration.seconds(1.1), new KeyValue(tri.strokeDashOffsetProperty(), -112.0))
            );
            anim.setCycleCount(Animation.INDEFINITE);

            getChildren().add(tri);
            setAlignment(Pos.CENTER);
        }

        void start() {
            anim.play();
        }

        void stop() {
            anim.stop();
            tri.setStrokeDashOffset(0.0);
        }
    }
}
