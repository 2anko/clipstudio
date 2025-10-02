package videocutter.view;


import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import videocutter.view.PreviewPane;
import videocutter.view.LibraryPane;
import videocutter.view.TimelinePane;
import videocutter.view.ToolbarPane;


public class MainView {
    private final BorderPane root = new BorderPane();
    private final LibraryPane libraryPane = new LibraryPane();
    private final PreviewPane previewPane = new PreviewPane();
    private final TimelinePane timelinePane = new TimelinePane();
    private final ToolbarPane toolbarPane = new ToolbarPane();


    public MainView() {
        SplitPane center = new SplitPane();
        center.setOrientation(Orientation.HORIZONTAL);
        center.getItems().addAll(libraryPane.getRoot(), previewPane.getRoot());
        center.setDividerPositions(0.25);


        root.setTop(toolbarPane.getRoot());
        root.setCenter(center);
        root.setBottom(timelinePane.getRoot());
    }


    public BorderPane getRoot() { return root; }
    public LibraryPane library() { return libraryPane; }
    public PreviewPane preview() { return previewPane; }
    public TimelinePane timeline() { return timelinePane; }
    public ToolbarPane toolbar() { return toolbarPane; }
}