package videocutter.controller;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import videocutter.model.Project;
import videocutter.model.TimelineClip;
import videocutter.model.VideoAsset;
import videocutter.model.VideoRepository;
import videocutter.service.FfmpegService;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ExportController {

    private final VideoRepository repo;
    private final FfmpegService ff;
    private final Project project;

    public ExportController(VideoRepository repo, FfmpegService ff, Project project) {
        this.repo = repo; this.ff = ff; this.project = project;
    }


    public void exportAsync(Window owner) {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("MP4", "*.mp4"));
        fc.setInitialFileName("export.mp4");
        File save = fc.showSaveDialog(owner);
        if (save == null) return;


        Task<Void> t = new Task<>() {
            @Override protected Void call() throws Exception {
                List<Path> tempCuts = new ArrayList<>();
                for (TimelineClip clip : project.clips()) {
                    VideoAsset asset = repo.findById(clip.assetId());
                    Path in = repo.materializeToTemp(asset.id()).toPath();
                    Path cut = ff.cut(in, clip.startMs(), clip.endMs());
                    tempCuts.add(cut);
                }
                ff.concat(tempCuts, save.toPath());
                return null;
            }
        };
        new Thread(t, "export-thread").start();
    }
}
