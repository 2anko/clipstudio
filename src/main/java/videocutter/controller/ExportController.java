package videocutter.controller;

import javafx.concurrent.Task;
import javafx.scene.control.Alert;
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
        this.repo = repo;
        this.ff = ff;
        this.project = project;
    }

    /**
     * Starts an export in a background thread.
     * @return the Task if export started, or null if user cancelled the file dialog.
     */
    public Task<Void> exportAsync(Window owner, Runnable onSuccess) {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("MP4", "*.mp4"));
        fc.setInitialFileName("export.mp4");
        File save = fc.showSaveDialog(owner);
        if (save == null) return null;

        Task<Void> t = new Task<>() {
            @Override
            protected Void call() throws Exception {
                List<FfmpegService.Segment> segs = new ArrayList<>();

                for (TimelineClip clip : project.clips()) {
                    VideoAsset asset = repo.findById(clip.assetId());
                    if (asset == null) continue;

                    Path input = repo.materializeToTemp(asset.id()).toPath();
                    segs.add(new FfmpegService.Segment(input, clip.startMs(), clip.endMs()));
                }

                if (segs.isEmpty()) {
                    throw new IllegalStateException("Nothing to export: timeline has no clips.");
                }

                updateProgress(0, 1);
                ff.exportTimeline(segs, save.toPath(), p -> updateProgress(p, 1.0));
                return null;
            }
        };

        t.setOnSucceeded(e -> { if (onSuccess != null) onSuccess.run(); });

        t.setOnFailed(e -> {
            Throwable ex = t.getException();
            Alert a = new Alert(Alert.AlertType.ERROR);
            a.setTitle("Export failed");
            a.setHeaderText("Export failed");
            a.setContentText(ex == null ? "Unknown error" : ex.getMessage());
            a.initOwner(owner);
            a.show();
        });

        new Thread(t, "export-thread").start();
        return t;
    }
}
