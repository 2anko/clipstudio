package videocutter.controller;

import javafx.concurrent.Task;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import videocutter.app.Main;
import videocutter.model.Project;
import videocutter.model.Resolution;
import videocutter.model.TimelineClip;
import videocutter.model.VideoAsset;
import videocutter.model.VideoRepository;
import videocutter.service.FfmpegService;

import java.io.File;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ExportController {
    private static final Logger LOG = LoggerFactory.getLogger(ExportController.class);
    private final VideoRepository repo;
    private final FfmpegService ff;
    private final Project project;

    public ExportController(VideoRepository repo, FfmpegService ff, Project project) {
        this.repo = repo;
        this.ff = ff;
        this.project = project;
    }

    public Task<Void> exportAsync(Window owner, Runnable onSuccess) {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("MP4", "*.mp4"));
        fc.setInitialFileName("export.mp4");
        File save = fc.showSaveDialog(owner);
        if (save == null) {
            return null;
        }

        // Get a fresh copy of the clips list for the background thread
        List<TimelineClip> clips = new ArrayList<>(project.clips());

        Task<Void> t = new Task<>() {
            @Override
            protected Void call() throws Exception {
                List<FfmpegService.Segment> segs = new ArrayList<>();
                for (TimelineClip clip : clips) {
                    VideoAsset asset = repo.findById(clip.assetId());
                    if (asset == null) {
                        LOG.warn("Skipping missing asset ID: {}", clip.assetId());
                        continue;
                    }

                    Path input = repo.materializeToTemp(asset.id()).toPath();
                    segs.add(new FfmpegService.Segment(input, clip.startMs(), clip.endMs()));
                }

                if (segs.isEmpty()) {
                    throw new IllegalStateException("Nothing to export: timeline has no valid clips.");
                }

                // Use a hardcoded resolution for now
                Resolution resolution = new Resolution(1920, 1080);

                updateProgress(0, 1);
                ff.exportTimeline(segs, resolution, save.toPath(), p -> updateProgress(p, 1.0));
                return null;
            }
        };

        t.setOnSucceeded(e -> {
            LOG.info("Export succeeded");
            if (onSuccess != null) {
                onSuccess.run();
            }
        });

        t.setOnFailed(e -> {
            Throwable ex = t.getException();
            LOG.error("Export failed", ex);
            Main.showAlert("Export Failed",
                    ex.getMessage() != null ? ex.getMessage() : "An unknown error occurred.",
                    owner);
        });

        new Thread(t, "export-thread").start();
        return t;
    }
}
