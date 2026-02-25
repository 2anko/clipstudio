package videocutter.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.FileChooser;
import videocutter.app.Main;
import videocutter.model.Project;
import videocutter.model.ProjectSerializer;
import videocutter.model.TimelineClip;
import videocutter.model.VideoAsset;
import videocutter.model.VideoRepository;
import videocutter.service.FfmpegService;
import videocutter.view.MainView;

import java.io.File;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainController {
    private final MainView view;
    private final Project project;
    private final VideoRepository repo;
    private final FfmpegService ff;
    private long previewAssetId = -1;
    private Path currentSaveFile = null; // null = not yet saved
    private TimelineClip selected;
    private long lastSourceMs = 0;
    private long selectedTimelineStartMs = 0;

    public MainController(MainView view, Project project, VideoRepository repo, FfmpegService ff) {
        this.view = view;
        this.project = project;
        this.repo = repo;
        this.ff = ff;
    }

    public void init() {
        // Import stays background so UI doesn't hang on large file copies.
        view.library().setOnImport(files -> {
            Task<Void> t = new Task<>() {
                @Override
                protected Void call() {
                    for (File f : files) {
                        repo.importMp4(f.toPath());
                        Platform.runLater(MainController.this::refreshLibrary);
                    }
                    return null;
                }
            };

            t.setOnFailed(e -> {
                Throwable ex = t.getException();
                ex.printStackTrace();
                Alert a = new Alert(Alert.AlertType.ERROR);
                a.setTitle("Import failed");
                a.setHeaderText("Import failed");
                a.setContentText(ex == null ? "Unknown error" : ex.getMessage());
                a.initOwner(view.getRoot().getScene().getWindow());
                a.show();
            });

            new Thread(t, "import-thread").start();
        });

        refreshLibrary();
        view.toolbar().refreshBtn().setOnAction(e -> refreshLibrary());

        view.toolbar().saveBtn().setOnAction(e -> saveProject());

        view.timeline().setOnScrub((clip, sourceMs, timelineMs) -> {
            selected = clip;
            lastSourceMs = sourceMs;
            selectedTimelineStartMs = timelineMs - (sourceMs - clip.startMs());

            VideoAsset a = repo.findById(clip.assetId());
            if (a == null) return;

            long assetId = a.id();
            if (previewAssetId != assetId) {
                previewAssetId = assetId;
                File media = repo.materializeToTemp(assetId);
                // Pass sourceMs so VLC parks at the right frame on first load,
                // not always at 0. seekWhenReady below handles same-asset seeks.
                view.preview().loadVideo(media, sourceMs);
            }

            view.preview().setInfo(a.title() + " [" + a.prettyDuration() + "]");
            view.preview().seekWhenReady(sourceMs);
            view.timeline().setPlayheadTimelineMs(timelineMs);
        });

        view.preview().setOnUserSeek(ms -> {
            if (selected == null) return;
            if (previewAssetId != selected.assetId()) return;
            lastSourceMs = ms;
            view.timeline().setPlayheadTimelineMs(selectedSourceToTimelineMs(ms));
        });

        view.preview().setOnTime(ms -> {
            if (selected == null) return;
            if (previewAssetId != selected.assetId()) return;

            // Enforce the clip's end boundary during playback.
            if (view.preview().isPlaying() && ms >= selected.endMs()) {
                // Try to advance to the next clip on the timeline.
                TimelineClip next = nextClip(selected);
                if (next != null) {
                    advanceToClip(next);
                } else {
                    // No next clip — park at the end.
                    view.preview().pause();
                    view.preview().seekWhenReady(selected.endMs());
                    lastSourceMs = selected.endMs();
                    view.timeline().setPlayheadTimelineMs(selectedSourceToTimelineMs(selected.endMs()));
                }
                return;
            }

            lastSourceMs = ms;
            if (view.preview().isPlaying()) {
                view.timeline().setPlayheadTimelineMs(selectedSourceToTimelineMs(ms));
            }
        });

        view.timeline().cutBtn().setOnAction(e -> {
            if (selected == null) return;

            long cutMs = lastSourceMs;

            // If the preview is actively playing this same asset, cut at the true playback time.
            if (previewAssetId == selected.assetId() && view.preview().isPlaying()) {
                cutMs = view.preview().getCurrentMs();
            }

            // Clamp to the selected clip range (avoid tiny clips)
            cutMs = Math.max(selected.startMs() + 100, Math.min(cutMs, selected.endMs() - 100));

            project.splitClipAt(selected, cutMs);

            selected = null;
            view.timeline().selectClip(null);
            view.timeline().setClips(project.clips());
        });

        // Double-click library row to add
        view.library().setOnAddRequested(assetId -> {
            VideoAsset asset = repo.findById(assetId);
            if (asset != null) {
                project.addClip(new TimelineClip(asset.id(), 0, asset.durationMs()));
                view.timeline().setClips(project.clips());
            }
        });

        view.toolbar().removeBtn().setOnAction(e -> {
            if (selected == null) return;
            project.removeClip(selected);
            selected = null;
            view.timeline().selectClip(null);
            view.timeline().setClips(project.clips());
            previewAssetId = -1;
        });

        view.toolbar().exportBtn().setOnAction(e -> {
            ExportController ec = new ExportController(repo, ff, project);
            Task<Void> task = ec.exportAsync(
                    view.getRoot().getScene().getWindow(),
                    () -> {
                        try { repo.deleteAllTempVideos(); } catch (SQLException ignore) {}
                        project.clear();
                        selected = null;
                        view.timeline().selectClip(null);
                        view.timeline().setClips(project.clips());
                        view.preview().clear();
                        refreshLibrary();
                    }
            );
            if (task != null) view.showExportOverlay(task);
            previewAssetId = -1;
        });


        view.timeline().setClips(project.clips());

        view.library().setOnDeleteMany(ids -> {
            if (ids == null || ids.isEmpty()) return;

            Alert confirm = new Alert(
                    Alert.AlertType.CONFIRMATION,
                    "Delete " + ids.size() + " video(s) from the database?\nThis cannot be undone.",
                    ButtonType.OK, ButtonType.CANCEL
            );
            confirm.initOwner(view.getRoot().getScene().getWindow());
            var res = confirm.showAndWait();
            if (res.isEmpty() || res.get() != ButtonType.OK) return;

            repo.deleteByIds(ids);

            Set<Long> deleted = new HashSet<>(ids);
            project.removeClipsByAssetIds(deleted);

            if (selected != null && deleted.contains(selected.assetId())) {
                selected = null;
                view.timeline().selectClip(null);
                view.preview().clear();
            }

            view.timeline().setClips(project.clips());
            refreshLibrary();
        });
    }

    /** Returns the clip immediately after the given one on the timeline, or null if it's the last. */
    private TimelineClip nextClip(TimelineClip clip) {
        List<TimelineClip> clips = project.clips();
        int idx = clips.indexOf(clip);
        return (idx >= 0 && idx + 1 < clips.size()) ? clips.get(idx + 1) : null;
    }

    /**
     * Computes the timeline start position (ms) of a clip by summing durations of all
     * preceding clips. This mirrors how TimelinePane lays clips out.
     */
    private long timelineStartOf(TimelineClip clip) {
        long acc = 0;
        for (TimelineClip c : project.clips()) {
            if (c == clip) return acc;
            acc += Math.max(0, c.endMs() - c.startMs());
        }
        return acc;
    }

    /**
     * Switches playback context to the given clip and begins playing from its startMs.
     * Handles both same-asset and different-asset transitions seamlessly.
     */
    private void advanceToClip(TimelineClip clip) {
        VideoAsset asset = repo.findById(clip.assetId());
        if (asset == null) return;

        selected = clip;
        lastSourceMs = clip.startMs();
        selectedTimelineStartMs = timelineStartOf(clip);

        view.timeline().selectClip(clip);
        view.timeline().setPlayheadTimelineMs(selectedTimelineStartMs);
        view.preview().setInfo(asset.title() + " [" + asset.prettyDuration() + "]");

        long assetId = asset.id();
        if (previewAssetId != assetId) {
            // Different source file — load and play from the clip's in-point atomically
            // so the pauseOnFirstPlaying logic doesn't fight us.
            previewAssetId = assetId;
            File media = repo.materializeToTemp(assetId);
            view.preview().loadVideoAndPlay(media, clip.startMs());
        } else {
            // Same source file — seek to the clip's in-point and resume.
            view.preview().seekWhenReady(clip.startMs());
            view.preview().play();
        }
    }

    private long selectedSourceToTimelineMs(long sourceMs) {
        if (selected == null) return 0;
        long clamped = Math.max(selected.startMs(), Math.min(sourceMs, selected.endMs()));
        return selectedTimelineStartMs + (clamped - selected.startMs());
    }

    // ---- Save / Load ----

    public void saveProject() {
        if (currentSaveFile == null) {
            FileChooser fc = new FileChooser();
            fc.setTitle("Save Project");
            fc.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("FrameCut Project", "*.framecut"));
            fc.setInitialFileName("project.framecut");
            File f = fc.showSaveDialog(view.getRoot().getScene().getWindow());
            if (f == null) return;
            currentSaveFile = f.toPath();
        }
        try {
            ProjectSerializer.save(project, currentSaveFile);
            view.toolbar().setProjectTitle(currentSaveFile.getFileName().toString()
                    .replace(".framecut", ""));
        } catch (Exception ex) {
            ex.printStackTrace();
            Main.showAlert("Save Failed", ex.getMessage(),
                    view.getRoot().getScene().getWindow());
        }
    }

    public void saveProjectAs() {
        currentSaveFile = null; // force file chooser
        saveProject();
    }

    /** Loads clips from a .framecut file into the project and refreshes the timeline. */
    public void loadFromFile(Path path) {
        try {
            var clips = ProjectSerializer.load(path);
            project.clear();
            for (var cd : clips) {
                // Verify the asset still exists in the DB before adding
                if (repo.findById(cd.assetId()) != null) {
                    project.addClip(new videocutter.model.TimelineClip(
                            cd.assetId(), cd.startMs(), cd.endMs()));
                }
            }
            currentSaveFile = path;
            view.toolbar().setProjectTitle(
                    path.getFileName().toString().replace(".framecut", ""));
            view.timeline().setClips(project.clips());
            refreshLibrary();
        } catch (Exception ex) {
            ex.printStackTrace();
            Main.showAlert("Open Failed", ex.getMessage(),
                    view.getRoot().getScene().getWindow());
        }
    }

    private void refreshLibrary() {
        List<VideoAsset> items = repo.listAll();
        view.library().list().setItems(FXCollections.observableArrayList(items));
    }
}