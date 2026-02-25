package videocutter.app;

import com.sun.jna.NativeLibrary;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import javafx.stage.Window;
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil;
import videocutter.controller.MainController;
import videocutter.model.Project;
import videocutter.model.VideoRepository;
import videocutter.service.FfmpegService;
import videocutter.view.AppShell;
import videocutter.view.MainView;
import videocutter.view.ProjectsView;

import java.io.File;
import java.nio.file.Path;
import java.sql.SQLException;
import javafx.stage.FileChooser;

public class Main extends Application {
    static {
        String vlcDir = System.getenv("VLC_HOME");
        if (vlcDir == null || vlcDir.isBlank()) {
            vlcDir = "C:\\Program Files\\VideoLAN\\VLC";
        }

        System.setProperty("jna.nosys", "true");
        System.setProperty("jna.library.path", vlcDir);
        NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcLibraryName(), vlcDir);
        NativeLibrary.addSearchPath("libvlccore", vlcDir);
        System.setProperty("VLC_PLUGIN_PATH", vlcDir + "\\plugins");
    }

    @Override
    public void start(Stage stage) throws SQLException {
        VideoRepository repo = new VideoRepository("videos.db");

        AppShell shell = new AppShell();
        ProjectsView home = new ProjectsView();
        shell.showProjects(home);

        home.setOnNewProject(() -> {
            openEditor(shell, home, repo, new Project(), null);
        });

        home.setOnOpenProject(() -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Open Project");
            fc.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("FrameCut Project", "*.framecut"));
            File f = fc.showOpenDialog(shell.getRoot().getScene().getWindow());
            if (f == null) return;
            Project project = new Project();
            MainController controller = openEditor(shell, home, repo, project, f.toPath());
            controller.loadFromFile(f.toPath());
        });

        Scene scene = new Scene(shell.getRoot(), 1280, 720);

        var css = Main.class.getResource("/videocutter/theme.css");
        if (css == null) {
            throw new IllegalStateException("Missing theme.css at src/main/resources/videocutter/theme.css");
        }
        scene.getStylesheets().add(css.toExternalForm());

        stage.setTitle("FrameCut");
        stage.setScene(scene);
        stage.show();
    }

    private static MainController openEditor(AppShell shell, ProjectsView home,
                                             VideoRepository repo, Project project, Path saveFile) {
        MainView editor = new MainView();
        MainController controller = new MainController(editor, project, repo, new FfmpegService());
        controller.init();
        editor.toolbar().backBtn().setOnAction(ev -> shell.showProjects(home));
        shell.showEditor(editor);
        return controller;
    }

    public static void showAlert(String title, String message, Window owner) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.initOwner(owner);
            alert.showAndWait();
        });
    }

    public static void main(String[] args) {
        launch(args);
    }
}