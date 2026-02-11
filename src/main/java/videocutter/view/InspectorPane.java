package videocutter.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

public class InspectorPane {
    private final VBox root = new VBox(10);

    public InspectorPane() {
        root.getStyleClass().addAll("panel", "inspector");
        root.setPadding(new Insets(16));

        Label title = new Label("Properties");
        title.getStyleClass().add("panel-title");

        Label hint = new Label("Select a clip on the timeline to edit its properties");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);

        root.setAlignment(Pos.TOP_LEFT);
        root.getChildren().addAll(title, hint);
        root.setMinWidth(280);
        root.setPrefWidth(320);
    }

    public VBox getRoot() { return root; }
}
