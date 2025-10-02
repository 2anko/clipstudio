package videocutter.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import videocutter.model.VideoAsset;
import javafx.scene.control.SelectionMode;
import javafx.scene.input.KeyCode;
import java.util.List;
import java.util.stream.Collectors;

import java.io.File;

public class LibraryPane {
    private final BorderPane root = new BorderPane();
    private final ListView<VideoAsset> list = new ListView<>();
    private final Button importBtn = new Button("Import .mp4");
    private final Button deleteBtn = new Button("Delete from DB");
    private final Label hint = new Label("Drag .mp4 files here to import (stored in DB)\nOr click Import.");
    public LibraryPane() {
        root.setPrefWidth(320);
        VBox top = new VBox(8, importBtn, hint, deleteBtn);
        top.setPadding(new Insets(10));
        hint.setWrapText(true);
        root.setTop(top);
        root.setCenter(list);
        BorderPane.setMargin(list, new Insets(6));

        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        list.setCellFactory(lv -> {
            ListCell<VideoAsset> cell = new ListCell<>() {
                @Override protected void updateItem(VideoAsset item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? null : item.title() + " (" + item.prettyDuration() + ")");
                }
            };

            // Start drag on the cell
            cell.setOnDragDetected(e -> {
                if (cell.getItem() == null) return;
                Dragboard db = cell.startDragAndDrop(TransferMode.COPY); // COPY or MOVE both fine
                ClipboardContent content = new ClipboardContent();
                content.putString(String.valueOf(cell.getItem().id()));
                db.setContent(content);

                // Optional: show a drag “ghost” so you can see it's active
                cell.snapshot(null, null); // ensure CSS applied
                db.setDragView(cell.snapshot(null, null));

                e.consume();
            });

            // Fallback: double-click a library row to add it to the timeline
            cell.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && cell.getItem() != null && onAddRequested != null) {
                    onAddRequested.onAdd(cell.getItem().id());
                }
            });

            return cell;
        });



        // Drag-and-drop import from OS
        root.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });

        root.setOnDragDropped(e -> {
            var files = e.getDragboard().getFiles();
            if (onImport != null && files != null && !files.isEmpty()) {
                onImport.onImport(files.toArray(new File[0]));
            }
            e.setDropCompleted(true);
            e.consume();
        });

        // Click-to-import
        importBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("MP4", "*.mp4"));
            var chosen = fc.showOpenMultipleDialog(root.getScene().getWindow());
            if (chosen != null && onImport != null) {
                onImport.onImport(chosen.toArray(new File[0]));
            }
        });

        deleteBtn.setOnAction(e -> {
            if (onDeleteMany == null) return;
            var ids = list.getSelectionModel().getSelectedItems()
                    .stream().map(VideoAsset::id).collect(Collectors.toList());
            if (!ids.isEmpty()) onDeleteMany.onDelete(ids);
        });

        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE && onDeleteMany != null) {
                var ids = list.getSelectionModel().getSelectedItems()
                        .stream().map(VideoAsset::id).collect(Collectors.toList());
                if (!ids.isEmpty()) onDeleteMany.onDelete(ids);
            }
        });
    }
    // Callback wiring
    public interface ImportHandler { void onImport(File[] files); }
    public interface AddRequested { void onAdd(long assetId); }
    private AddRequested onAddRequested;
    public void setOnAddRequested(AddRequested h) { this.onAddRequested = h; }
    private ImportHandler onImport;
    public void setOnImport(ImportHandler handler) { this.onImport = handler; }
    public interface DeleteManyHandler { void onDelete(List<Long> ids); }
    private DeleteManyHandler onDeleteMany;
    public void setOnDeleteMany(DeleteManyHandler h) { this.onDeleteMany = h; }


    public BorderPane getRoot() { return root; }
    public ListView<VideoAsset> list() { return list; }
}
