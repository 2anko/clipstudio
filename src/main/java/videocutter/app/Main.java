package videocutter.app;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import videocutter.controller.MainController;
import videocutter.model.Project;
import videocutter.model.VideoRepository;
import videocutter.service.FfmpegService;
import videocutter.view.AppShell;
import videocutter.view.MainView;
import videocutter.view.ProjectsView;

import java.sql.SQLException;

public class Main extends Application {
    @Override
    public void start(Stage stage) throws SQLException {
        VideoRepository repo = new VideoRepository("videos.db");

        AppShell shell = new AppShell();
        ProjectsView home = new ProjectsView();
        shell.showProjects(home);

        home.setOnNewProject(() -> {
            Project project = new Project();
            MainView editor = new MainView();
            MainController controller = new MainController(editor, project, repo, new FfmpegService());
            controller.init();

            // Back to projects
            editor.toolbar().backBtn().setOnAction(ev -> shell.showProjects(home));
            shell.showEditor(editor);
        });

        Scene scene = new Scene(shell.getRoot(), 1280, 720);

        // Put theme.css at: src/main/resources/videocutter/theme.css
        var css = Main.class.getResource("/videocutter/theme.css");
        if (css == null) {
            throw new IllegalStateException("Missing theme.css at src/main/resources/videocutter/theme.css");
        }
        scene.getStylesheets().add(css.toExternalForm());

        stage.setTitle("FrameCut");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) { launch(args); }
}
