package videocutter.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import videocutter.model.Project;
import videocutter.model.TimelineClip;
import videocutter.model.VideoAsset;
import videocutter.model.VideoRepository;
import videocutter.service.FfmpegService;
import videocutter.view.MainView;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;


import java.io.File;
import java.util.List;

public class MainController {
    private final MainView view;
    private final Project project;
    private final VideoRepository repo;
    private final FfmpegService ff;

    private TimelineClip selected;

    public MainController(MainView view, Project project, VideoRepository repo, FfmpegService ff) {
        this.view = view; this.project = project; this.repo = repo; this.ff = ff;
    }

    public void init() {
        // Library
        view.library().setOnImport(files -> {
            Platform.runLater(() -> {
                for (File f : files) repo.importMp4(f.toPath());
                refreshLibrary();
            });
        });
        refreshLibrary();

        view.toolbar().refreshBtn().setOnAction(e -> refreshLibrary());

        // Drop from library onto timeline
        view.timeline().setOnDrop(assetId -> {
            VideoAsset asset = repo.findById(assetId);
            if (asset != null) {
                project.addClip(new TimelineClip(asset.id(), 0, asset.durationMs()));
                view.timeline().setClips(project.clips());
            }
        });

        // Double-click library row to add
        view.library().setOnAddRequested(assetId -> {
            VideoAsset asset = repo.findById(assetId);
            if (asset != null) {
                project.addClip(new TimelineClip(asset.id(), 0, asset.durationMs()));
                view.timeline().setClips(project.clips());
            }
        });

        view.timeline().setOnSelect(clip -> {
            selected = clip;
            VideoAsset a = repo.findById(clip.assetId());
            File temp = repo.materializeToTemp(a.id());
            view.preview().loadVideo(temp, clip.startMs(), clip.endMs());
            view.preview().setInfo(a.title() + " [" + a.prettyDuration() + "]");
        });

        view.preview().setOnTrimChanged((start, end) -> {
            if (selected == null) return;
            selected.setStartMs(start);
            selected.setEndMs(end);
            view.timeline().setClips(project.clips());
        });

        view.toolbar().removeBtn().setOnAction(e -> {
            if (selected == null) return;
            project.removeClip(selected);
            selected = null;
            view.timeline().setClips(project.clips());
        });

        view.toolbar().exportBtn().setOnAction(e -> new ExportController(repo, ff, project).exportAsync(view.getRoot().getScene().getWindow()));

        view.timeline().setClips(project.clips());

        view.library().setOnDeleteMany(ids -> {
            if (ids == null || ids.isEmpty()) return;

            // Confirm
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete " + ids.size() + " video(s) from the database?\nThis cannot be undone.",
                    ButtonType.OK, ButtonType.CANCEL);
            confirm.initOwner(view.getRoot().getScene().getWindow());
            var res = confirm.showAndWait();
            if (res.isEmpty() || res.get() != ButtonType.OK) return;

            // Delete from DB
            repo.deleteByIds(ids);

            // Remove any timeline clips referencing these assets
            java.util.Set<Long> deleted = new java.util.HashSet<>(ids);
            project.removeClipsByAssetIds(deleted);

            // If the currently selected clip was deleted, clear selection and preview
            if (selected != null && deleted.contains(selected.assetId())) {
                selected = null;
                view.preview().clear();
            }

            // Refresh UI
            view.timeline().setClips(project.clips());
            refreshLibrary();  // you already have this helper in your controller
        });

    }


    private void refreshLibrary() {
        List<VideoAsset> items = repo.listAll();
        view.library().list().setItems(FXCollections.observableArrayList(items));
    }
}
