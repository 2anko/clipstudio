package videocutter.app;


import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import videocutter.controller.MainController;
import videocutter.model.Project;
import videocutter.model.VideoRepository;
import videocutter.service.FfmpegService;
import videocutter.view.MainView;


public class Main extends Application {
    @Override
    public void start(Stage stage) {
        VideoRepository repo = new VideoRepository("videos.db");
        Project project = new Project();
        MainView view = new MainView();
        MainController controller = new MainController(view, project, repo, new FfmpegService());
        controller.init();


        Scene scene = new Scene(view.getRoot(), 1280, 720);
        stage.setTitle("VideoCutter — Clip & Merge (MVP)");
        stage.setScene(scene);
        stage.show();
    }


    public static void main(String[] args) { launch(args); }
}