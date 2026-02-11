package videocutter.view;
import javafx.scene.layout.StackPane;

public class AppShell {
    private final StackPane root = new StackPane();

    private ProjectsView projectsView;
    private MainView editorView;

    public StackPane getRoot() { return root; }

    public void showProjects(ProjectsView v) {
        this.projectsView = v;
        root.getChildren().setAll(v.getRoot());
    }

    public void showEditor(MainView v) {
        this.editorView = v;
        root.getChildren().setAll(v.getRoot());
    }

    public ProjectsView projectsView() { return projectsView; }
    public MainView editorView() { return editorView; }
}
