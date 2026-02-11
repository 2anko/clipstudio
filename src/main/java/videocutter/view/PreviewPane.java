package videocutter.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;

import java.io.File;

public class PreviewPane {
    private final BorderPane root = new BorderPane();

    private final MediaView mediaView = new MediaView();
    private MediaPlayer mediaPlayer;

    private final StackPane frame = new StackPane();
    private final Label emptyHint = new Label("Preview will appear here");
    private final Button bigPlay = new Button("▶");

    private final Label timeLabel = new Label("00:00:00");
    private final Button playBtn = new Button("▶");
    private final Label clipInfo = new Label("");

    // ✅ normal progress bar
    private final Slider progress = new Slider(0, 1, 0);

    private boolean ready = false;
    private long pendingSeekMs = -1;

    public PreviewPane() {
        root.getStyleClass().addAll("panel", "preview");

        frame.getStyleClass().add("preview-frame");
        frame.setPadding(new Insets(24));
        frame.setMinHeight(420);

        mediaView.setPreserveRatio(true);
        mediaView.setFitWidth(900);
        mediaView.setFitHeight(520);

        emptyHint.getStyleClass().add("muted");
        bigPlay.getStyleClass().addAll("btn", "big-play");
        bigPlay.setOnAction(e -> togglePlay());

        StackPane.setAlignment(timeLabel, Pos.TOP_RIGHT);
        timeLabel.getStyleClass().add("time-label");

        frame.getChildren().addAll(mediaView, emptyHint, bigPlay, timeLabel);
        root.setCenter(frame);
        BorderPane.setMargin(frame, new Insets(14));

        // progress slider styling hook
        progress.getStyleClass().add("progress-slider");
        progress.setDisable(true);

        // seek behavior
        progress.valueChangingProperty().addListener((obs, was, is) -> {
            if (!is) { // released
                seekTo((long) progress.getValue());
            }
        });
        progress.setOnMousePressed(e -> seekTo((long) progress.getValue()));

        // bottom controls
        playBtn.getStyleClass().addAll("btn", "icon-btn");
        clipInfo.getStyleClass().add("muted");
        playBtn.setOnAction(e -> togglePlay());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox transport = new HBox(10, playBtn, clipInfo, spacer);
        transport.setPadding(new Insets(8, 16, 12, 16));
        transport.setAlignment(Pos.CENTER_LEFT);

        VBox bottom = new VBox(10, progress, transport);
        bottom.setPadding(new Insets(0, 14, 14, 14));
        root.setBottom(bottom);

        updateEmptyState();
    }

    private void togglePlay() {
        if (mediaPlayer == null) return;
        var status = mediaPlayer.getStatus();
        if (status == MediaPlayer.Status.PLAYING) mediaPlayer.pause();
        else mediaPlayer.play();
    }

    private void updateEmptyState() {
        boolean empty = (mediaPlayer == null);
        emptyHint.setVisible(empty);
        bigPlay.setVisible(empty);
        timeLabel.setVisible(true);
    }

    // Keep signature so MainController doesn't break
    public void loadVideo(File file, long startMsIgnored, long endMsIgnored) {
        loadVideo(file);
    }

    public void loadVideo(File file) {
        if (mediaPlayer != null) mediaPlayer.dispose();
        ready = false;
        pendingSeekMs = -1;

        Media media = new Media(file.toURI().toString());
        mediaPlayer = new MediaPlayer(media);
        mediaView.setMediaPlayer(mediaPlayer);

        mediaPlayer.setOnReady(() -> {
            ready = true;
            long durMs = (long) media.getDuration().toMillis();

            progress.setDisable(false);
            progress.setMin(0);
            progress.setMax(Math.max(1, durMs));
            progress.setValue(0);

            mediaPlayer.currentTimeProperty().addListener((obs, oldT, newT) -> {
                long ms = (long) newT.toMillis();
                timeLabel.setText(formatTime(newT));
                if (!progress.isValueChanging()) {
                    progress.setValue(ms);
                }
            });

            mediaPlayer.setOnEndOfMedia(() -> {
                mediaPlayer.pause();
            });

            if (pendingSeekMs >= 0) {
                long tmp = pendingSeekMs;
                pendingSeekMs = -1;
                seekTo(tmp);
            }
        });

        updateEmptyState();
    }

    private String formatTime(Duration d) {
        long ms = (long) d.toMillis();
        long s = ms / 1000;
        long hh = s / 3600;
        long mm = (s % 3600) / 60;
        long ss = s % 60;
        return String.format("%02d:%02d:%02d", hh, mm, ss);
    }

    public void setInfo(String text) { clipInfo.setText(text); }

    public void seekTo(long ms) {
        if (mediaPlayer == null || !ready) return;
        long target = Math.max(0, Math.min(ms, (long) mediaPlayer.getTotalDuration().toMillis()));
        mediaPlayer.pause();
        mediaPlayer.seek(Duration.millis(target));
        progress.setValue(target);
    }

    public void seekWhenReady(long ms) {
        if (mediaPlayer == null || !ready) {
            pendingSeekMs = ms;
            return;
        }
        seekTo(ms);
    }

    public void clear() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.dispose();
            mediaPlayer = null;
        }
        ready = false;
        pendingSeekMs = -1;
        mediaView.setMediaPlayer(null);
        setInfo("");
        timeLabel.setText("00:00:00");
        progress.setDisable(true);
        progress.setMin(0);
        progress.setMax(1);
        progress.setValue(0);
        updateEmptyState();
    }

    public BorderPane getRoot() { return root; }
}
