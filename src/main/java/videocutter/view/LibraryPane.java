package videocutter.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import videocutter.model.VideoAsset;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

public class LibraryPane {
    private final BorderPane root = new BorderPane();

    private final TextField search = new TextField();
    private final ToggleGroup mediaGroup = new ToggleGroup();
    private final ToggleButton all = tab("All", true);
    private final ToggleButton video = tab("Video", false);
    private final ToggleButton audio = tab("Audio", false);
    private final ToggleButton image = tab("Image", false);

    private final StackPane dropzone = new StackPane();
    private final ListView<VideoAsset> list = new ListView<>();

    private final Button deleteBtn = new Button("Delete");

    public LibraryPane() {
        root.getStyleClass().addAll("panel", "sidebar");
        root.setPrefWidth(320);

        // Header row
        Label title = new Label("MEDIA");
        title.getStyleClass().add("section-title");

        Button gridBtn = new Button("▦");
        Button listBtn = new Button("≡");
        gridBtn.getStyleClass().addAll("btn", "icon-btn");
        listBtn.getStyleClass().addAll("btn", "icon-btn");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(8, title, spacer, gridBtn, listBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        // Search
        search.setPromptText("Search media...");
        search.getStyleClass().add("search");
        search.setPrefWidth(280);

        // Tabs row
        HBox tabs = new HBox(6, all, video, audio, image);
        tabs.getStyleClass().add("tab-row");

        // Dropzone
        dropzone.getStyleClass().add("dropzone");
        dropzone.setPrefHeight(86);

        Label dz = new Label("Drop files or click to upload");
        dz.getStyleClass().add("muted");
        dropzone.getChildren().add(dz);
        dropzone.setOnMouseClicked(e -> openFilePicker());

        VBox top = new VBox(10, header, search, tabs, dropzone);
        top.setPadding(new Insets(14, 12, 10, 12));
        root.setTop(top);

        // List styling + behavior
        list.getStyleClass().add("dark-list");
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        list.setCellFactory(lv -> {
            ListCell<VideoAsset> cell = new ListCell<>() {
                @Override protected void updateItem(VideoAsset item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                    } else {
                        setText(item.title() + "  •  " + item.prettyDuration());
                    }
                }
            };

            cell.setOnDragDetected(e -> {
                if (cell.getItem() == null) return;
                Dragboard db = cell.startDragAndDrop(TransferMode.COPY);
                ClipboardContent content = new ClipboardContent();
                content.putString(String.valueOf(cell.getItem().id()));
                db.setContent(content);
                db.setDragView(cell.snapshot(null, null));
                e.consume();
            });

            cell.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && cell.getItem() != null && onAddRequested != null) {
                    onAddRequested.onAdd(cell.getItem().id());
                }
            });

            return cell;
        });

        root.setCenter(list);
        BorderPane.setMargin(list, new Insets(0, 10, 10, 10));

        // Bottom actions
        deleteBtn.getStyleClass().addAll("btn", "btn-secondary");
        deleteBtn.setOnAction(e -> deleteSelected());

        HBox bottom = new HBox(deleteBtn);
        bottom.setPadding(new Insets(0, 12, 12, 12));
        root.setBottom(bottom);

        // Drag-and-drop import from OS (whole sidebar)
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

        // Delete key support
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE) deleteSelected();
        });
    }

    private ToggleButton tab(String text, boolean selected) {
        ToggleButton b = new ToggleButton(text);
        b.setToggleGroup(mediaGroup);
        b.setSelected(selected);
        b.getStyleClass().add("tab");
        return b;
    }

    private void openFilePicker() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("MP4", "*.mp4"));
        var chosen = fc.showOpenMultipleDialog(root.getScene().getWindow());
        if (chosen != null && onImport != null) onImport.onImport(chosen.toArray(new File[0]));
    }

    private void deleteSelected() {
        if (onDeleteMany == null) return;
        var ids = list.getSelectionModel().getSelectedItems()
                .stream().map(VideoAsset::id).collect(Collectors.toList());
        if (!ids.isEmpty()) onDeleteMany.onDelete(ids);
    }

    // Callback wiring
    public interface ImportHandler { void onImport(File[] files); }
    public interface AddRequested { void onAdd(long assetId); }
    public interface DeleteManyHandler { void onDelete(List<Long> ids); }

    private ImportHandler onImport;
    private AddRequested onAddRequested;
    private DeleteManyHandler onDeleteMany;

    public void setOnImport(ImportHandler handler) { this.onImport = handler; }
    public void setOnAddRequested(AddRequested h) { this.onAddRequested = h; }
    public void setOnDeleteMany(DeleteManyHandler h) { this.onDeleteMany = h; }

    public BorderPane getRoot() { return root; }
    public ListView<VideoAsset> list() { return list; }
}
