package videocutter.view;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import videocutter.model.TimelineClip;

import java.util.List;

public class TimelinePane {
    private final VBox root = new VBox();
    private final FlowPane track = new FlowPane();
    private final Label hint = new Label("Drag videos here to add to timeline. Click a clip to trim.");

    public TimelinePane() {
        root.setPadding(new Insets(8));
        track.setHgap(8);
        track.setPrefWrapLength(10000);
        root.getChildren().addAll(hint, track);
        VBox.setVgrow(track, Priority.ALWAYS);

        track.setOnDragOver(e -> {
            if (e.getDragboard().hasString()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });

        track.setOnDragDropped(e -> {
            String idStr = e.getDragboard().getString();
            if (idStr != null && onDrop != null) onDrop.onDrop(Long.parseLong(idStr));
            e.setDropCompleted(true);
            e.consume();
        });
    }

    public interface DropHandler { void onDrop(long assetId); }
    private DropHandler onDrop;
    public void setOnDrop(DropHandler h) { onDrop = h; }

    public void setClips(List<TimelineClip> clips) {
        track.getChildren().clear();
        for (TimelineClip c : clips) {
            ClipNode node = new ClipNode(c);
            track.getChildren().add(node);
            node.setOnMouseClicked(e -> { if (onSelect != null) onSelect.onSelect(c); });
        }
    }

    // a simple visual chip for a clip
    static class ClipNode extends HBox {
        ClipNode(TimelineClip clip) {
            this.setPadding(new Insets(10));
            this.setStyle("-fx-background-color: #30354a; -fx-border-color: #596080; -fx-border-radius: 6; -fx-background-radius: 6;");
            Label label = new Label(clip.display());
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            this.getChildren().addAll(label, spacer);
        }
    }

    public interface SelectHandler { void onSelect(TimelineClip clip); }
    private SelectHandler onSelect;
    public void setOnSelect(SelectHandler h) { onSelect = h; }

    public VBox getRoot() { return root; }
}
