package videocutter.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

public class ToolbarPane {
    private final HBox root = new HBox(10);

    private final Button backBtn = new Button("‹  Projects");
    private final Label title = new Label("Untitled Project");

    // keep these so MainController keeps working
    private final Button refreshBtn = new Button("⟳");
    private final Button removeBtn  = new Button("🗑");
    private final Button exportBtn  = new Button("Export");

    // extra “mock-like” controls (optional wiring later)
    private final Button undoBtn = new Button("↶");
    private final Button redoBtn = new Button("↷");
    private final Button textBtn = new Button("T");
    private final Button shapeBtn = new Button("□");

    private final ComboBox<String> aspect = new ComboBox<>();
    private final Button saveBtn = new Button("Save");

    public ToolbarPane() {
        root.getStyleClass().add("topbar");
        root.setPadding(new Insets(10, 14, 10, 14));
        root.setAlignment(Pos.CENTER_LEFT);

        backBtn.getStyleClass().addAll("btn", "btn-ghost");
        title.getStyleClass().add("top-title");

        // icon buttons
        for (var b : new Button[]{undoBtn, redoBtn, textBtn, shapeBtn, refreshBtn, removeBtn}) {
            b.getStyleClass().addAll("btn", "icon-btn");
            b.setFocusTraversable(false);
        }

        exportBtn.getStyleClass().addAll("btn", "btn-primary");
        saveBtn.getStyleClass().addAll("btn", "btn-secondary");

        aspect.getItems().addAll("16:9", "9:16", "1:1", "4:3");
        aspect.getSelectionModel().select("16:9");
        aspect.getStyleClass().add("combo-dark");
        aspect.setPrefWidth(90);

        // layout groups
        HBox left = new HBox(10, backBtn, title);
        left.setAlignment(Pos.CENTER_LEFT);

        HBox center = new HBox(8,
                undoBtn, redoBtn,
                new Separator(), textBtn, shapeBtn,
                new Separator(), refreshBtn, removeBtn
        );
        center.setAlignment(Pos.CENTER);

        HBox right = new HBox(10, aspect, saveBtn, exportBtn);
        right.setAlignment(Pos.CENTER_RIGHT);

        Region s1 = new Region();
        Region s2 = new Region();
        HBox.setHgrow(s1, Priority.ALWAYS);
        HBox.setHgrow(s2, Priority.ALWAYS);

        root.getChildren().addAll(left, s1, center, s2, right);
    }

    public HBox getRoot() { return root; }

    // used by Main + MainController
    public Button backBtn() { return backBtn; }
    public Button refreshBtn() { return refreshBtn; }
    public Button removeBtn() { return removeBtn; }
    public Button exportBtn() { return exportBtn; }

    // optional
    public void setProjectTitle(String name) { title.setText(name); }
}
