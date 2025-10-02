package videocutter.view;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;
import videocutter.view.controls.RangeTrimBar;

import java.io.File;

public class PreviewPane {
    private final BorderPane root = new BorderPane();
    private final MediaView mediaView = new MediaView();
    private MediaPlayer mediaPlayer;
    private final Label clipInfo = new Label("No clip selected.");
    private final Button playBtn = new Button("Play/Pause");
    private final RangeTrimBar trimBar = new RangeTrimBar();

    public PreviewPane() {
        mediaView.setPreserveRatio(true);
        mediaView.setFitWidth(800);
        mediaView.setFitHeight(450);
        BorderPane.setMargin(mediaView, new Insets(10));
        BorderPane.setMargin(trimBar, new Insets(10, 20, 10, 20));
        HBox controls = new HBox(10, playBtn, clipInfo);
        controls.setPadding(new Insets(8));


        root.setCenter(mediaView);
        root.setBottom(new BorderPane(trimBar, null, null, null, controls));


        playBtn.setOnAction(e -> {
            if (mediaPlayer == null) return;
            var status = mediaPlayer.getStatus();
            if (status == MediaPlayer.Status.PLAYING) mediaPlayer.pause();
            else mediaPlayer.play();
        });


        trimBar.setOnChanged((startMs, endMs) -> {
            if (onTrimChanged != null) onTrimChanged.onChanged(startMs, endMs);
            if (mediaPlayer != null) {
                mediaPlayer.pause();
                mediaPlayer.setStartTime(Duration.millis(startMs));
                mediaPlayer.setStopTime(Duration.millis(endMs));
                mediaPlayer.seek(Duration.millis(startMs));
            }
        });
    }

    public void loadVideo(File tempFile, long startMs, long endMs) {
        if (mediaPlayer != null) mediaPlayer.dispose();
        Media media = new Media(tempFile.toURI().toString());
        mediaPlayer = new MediaPlayer(media);
        mediaView.setMediaPlayer(mediaPlayer);

        mediaPlayer.setOnReady(() -> {
            long durMs = (long) media.getDuration().toMillis();

            trimBar.setBounds(0, durMs);
            // Clamp incoming values to media duration just in case
            long s = Math.max(0, Math.min(startMs, durMs));
            long e = Math.max(s + 100, Math.min(endMs, durMs));

            trimBar.setValues(s, e);

            // 🔒 Constrain preview to the trimmed window
            mediaPlayer.setStartTime(Duration.millis(s));
            mediaPlayer.setStopTime(Duration.millis(e));
            mediaPlayer.seek(Duration.millis(s));

            // ⏹ When reaching end trim, pause and rest at end
            mediaPlayer.setOnEndOfMedia(() -> {
                mediaPlayer.pause();
                mediaPlayer.seek(mediaPlayer.getStopTime());
            });
        });
    }

    public void setInfo(String text) { clipInfo.setText(text); }

    public void clear() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.dispose();
            mediaPlayer = null;
        }
        mediaView.setMediaPlayer(null);
        setInfo("No clip selected.");
        trimBar.setBounds(0, 1000);
        trimBar.setValues(0, 1000);
    }


    // Callback when user trims
    public interface TrimChanged { void onChanged(long startMs, long endMs); }
    private TrimChanged onTrimChanged;
    public void setOnTrimChanged(TrimChanged handler) { this.onTrimChanged = handler; }


    public BorderPane getRoot() { return root; }
    public RangeTrimBar trimBar() { return trimBar; }
}
