package videocutter.view;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;

public class ToolbarPane {
    private final HBox root = new HBox(10);
    private final Button addFromDB = new Button("Refresh Library");
    private final Button removeSelected = new Button("Remove Clip");
    private final Button exportBtn = new Button("Export MP4");


    public ToolbarPane() {
        root.setPadding(new Insets(8));
        root.getChildren().addAll(addFromDB, new Separator(), removeSelected, new Separator(), exportBtn);
    }

    public HBox getRoot() { return root; }
    public Button refreshBtn() { return addFromDB; }
    public Button removeBtn() { return removeSelected; }
    public Button exportBtn() { return exportBtn; }
}