package videocutter.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import videocutter.model.Project;
import videocutter.model.TimelineClip;
import videocutter.model.VideoAsset;
import videocutter.model.VideoRepository;
import videocutter.service.FfmpegService;
import videocutter.view.MainView;

import java.io.File;
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

        view.timeline().setOnScrub((clip, sourceMs, timelineMs) -> {
            selected = clip;
            lastSourceMs = sourceMs;
            selectedTimelineStartMs = timelineMs - (sourceMs - clip.startMs());

            VideoAsset a = repo.findById(clip.assetId());
            if (a == null) return;

            long assetId = a.id();
            if (previewAssetId != assetId) {
                previewAssetId = assetId;

                // No preview proxies: VLC handles playback; FFmpeg handles frame-accurate scrubbing.
                File media = repo.materializeToTemp(assetId);
                view.preview().loadVideo(media);
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

    private long selectedSourceToTimelineMs(long sourceMs) {
        if (selected == null) return 0;
        long clamped = Math.max(selected.startMs(), Math.min(sourceMs, selected.endMs()));
        return selectedTimelineStartMs + (clamped - selected.startMs());
    }

    private void refreshLibrary() {
        List<VideoAsset> items = repo.listAll();
        view.library().list().setItems(FXCollections.observableArrayList(items));
    }
}
