package videocutter.view;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javafx.scene.shape.Rectangle;
import com.sun.jna.NativeLibrary;
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil;

import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.javafx.videosurface.ImageViewVideoSurface;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * Hybrid preview:
 *  - Playback: VLC (libVLC) for robust real-time decode + A/V.
 *  - Scrub while paused: FFmpeg extracts a single frame to an overlay ImageView (frame-accurate, no keyframe snapping).
 */
public class PreviewPane {

    private final BorderPane root = new BorderPane();

    // VLC renders into this ImageView
    private final ImageView vlcView = new ImageView();

    // Scrub overlay (FFmpeg-extracted frame) goes on top of VLC view
    private final ImageView scrubOverlay = new ImageView();

    private File currentFile;

    private final StackPane frame = new StackPane();
    private final Label emptyHint = new Label("Preview will appear here");
    private final Button bigPlay = new Button("▶");

    private LongConsumer onUserSeek;
    private LongConsumer onTime;

    private final Label timeLabel = new Label("00:00:00");
    private final Button playBtn = new Button("▶");
    private final Label clipInfo = new Label("");

    private final Slider progress = new Slider(0, 1, 0);

    // VLCJ
    private MediaPlayerFactory vlcFactory;
    private EmbeddedMediaPlayer vlcPlayer;

    // We track the UI playhead time separately so scrub can be accurate even when VLC is paused at a different time.
    private long desiredMs = 0;
    private long mediaLengthMs = 1;

    // "Start paused" logic (ONLY for initial load).
    private boolean pauseOnFirstPlaying = false;
    private long initialPauseMs = 0;

    // When user hits Play from a scrubbed position, we may need to apply seek once VLC reports "playing".
    private long seekOnNextPlayingEvent = -1;
    private boolean keepPlayingAfterSeek = false;

    // Hide scrub overlay after VLC catches up to a target time during playback.
    private long hideScrubWhenNearMs = -1;

    // UI polling (safe for JavaFX)
    private Timeline uiTimer;

    // FFmpeg scrubbing worker (single thread, but we clear the queue so we don't fall behind)
    private final ThreadPoolExecutor scrubExec;
    private final AtomicLong scrubRequestId = new AtomicLong(0);

    private static final long UI_TICK_MS = 100;
    private long lastShownSec = -1;

    public PreviewPane() {
        root.getStyleClass().addAll("panel", "preview");

        frame.getStyleClass().add("preview-frame");
        frame.setPadding(new Insets(24));
        frame.setMinHeight(420);

        // Configure VLC image view
        vlcView.setPreserveRatio(true);
        vlcView.setSmooth(true);

        // Prevent layout feedback loop: VLC updates the ImageView image frequently.
        // Make these unmanaged so their image size never affects parent sizing.
        vlcView.setManaged(false);
        scrubOverlay.setManaged(false);

        // Clip video to the preview frame so it can never "spill" outside.
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(frame.widthProperty());
        clip.heightProperty().bind(frame.heightProperty());
        frame.setClip(clip);

        StackPane.setAlignment(vlcView, Pos.CENTER);
        StackPane.setAlignment(scrubOverlay, Pos.CENTER);

        // Configure scrub overlay image view
        scrubOverlay.setPreserveRatio(true);
        scrubOverlay.setSmooth(true);
        scrubOverlay.setVisible(false);

        emptyHint.getStyleClass().add("muted");

        bigPlay.getStyleClass().addAll("btn", "big-play");
        bigPlay.setOnAction(e -> togglePlay());

        StackPane.setAlignment(timeLabel, Pos.TOP_RIGHT);
        timeLabel.getStyleClass().add("time-label");

        frame.getChildren().addAll(vlcView, scrubOverlay, emptyHint, bigPlay, timeLabel);
        root.setCenter(frame);
        BorderPane.setMargin(frame, new Insets(14));

        Runnable resize = () -> {
            double w = Math.max(0, frame.getWidth() - 48);
            double h = Math.max(0, frame.getHeight() - 48);
            vlcView.setFitWidth(w);
            vlcView.setFitHeight(h);
            scrubOverlay.setFitWidth(w);
            scrubOverlay.setFitHeight(h);
        };

        frame.widthProperty().addListener((obs, ov, nv) -> resize.run());
        frame.heightProperty().addListener((obs, ov, nv) -> resize.run());
        Platform.runLater(resize); // run once after first layout

        progress.getStyleClass().add("progress-slider");
        progress.setDisable(true);

        // slider scrubbing
        progress.valueChangingProperty().addListener((obs, was, is) -> {
            if (!is) seekToUser((long) progress.getValue());
        });
        progress.setOnMousePressed(e -> seekToUser((long) progress.getValue()));

        playBtn.getStyleClass().addAll("btn", "icon-btn");
        playBtn.setOnAction(e -> togglePlay());
        clipInfo.getStyleClass().add("muted");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox transport = new HBox(10, playBtn, clipInfo, spacer);
        transport.setPadding(new Insets(8, 16, 12, 16));
        transport.setAlignment(Pos.CENTER_LEFT);

        VBox bottom = new VBox(10, progress, transport);
        bottom.setPadding(new Insets(0, 14, 14, 14));
        root.setBottom(bottom);

        scrubExec = new ThreadPoolExecutor(
                1, 1,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                new ThreadFactory() {
                    @Override public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "ffmpeg-scrub");
                        t.setDaemon(true);
                        return t;
                    }
                }
        );

        startUiTimer();
        updateEmptyState();
    }

    // Kept for compatibility
    public void loadVideo(File file, long startMsIgnored, long endMsIgnored) {
        loadVideo(file);
    }

    public void loadVideo(File file) {
        currentFile = file;
        desiredMs = 0;
        mediaLengthMs = 1;

        // Invalidate any pending scrub frames
        scrubRequestId.incrementAndGet();
        scrubOverlay.setImage(null);
        scrubOverlay.setVisible(false);

        ensureVlc();

        // Stop old media if any
        if (vlcPlayer != null) {
            try { vlcPlayer.controls().stop(); } catch (Exception ignored) {}
        }

        progress.setDisable(false);
        progress.setMin(0);
        progress.setMax(1);
        progress.setValue(0);

        // "Start paused" on first frame
        pauseOnFirstPlaying = true;
        initialPauseMs = 0;

        // Reset play-seek state
        seekOnNextPlayingEvent = -1;
        keepPlayingAfterSeek = false;
        hideScrubWhenNearMs = -1;

        // Start playback so VLC pipeline initializes, then we'll pause on first playing event
        String mrl = file.getAbsolutePath();
        vlcPlayer.media().play(mrl);

        // While we wait for VLC, show a frame via FFmpeg (fast user feedback)
        scrubOverlay.setVisible(true);
        requestScrubFrame(0);

        updateEmptyState();
    }

    private void ensureVlc() {
        if (vlcPlayer != null) return;

        String vlcDir = System.getenv("VLC_HOME");
        if (vlcDir == null || vlcDir.isBlank()) {
            vlcDir = "C:\\Program Files\\VideoLAN\\VLC";
        }

        // Use forward slashes to avoid any weird escaping issues in VLC options
        String vlcDirFs = vlcDir.replace('\\', '/');
        String pluginsDir = (vlcDirFs + "/plugins");

        // Force JNA to search your installed VLC folder first
        NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcLibraryName(), vlcDir); // libvlc.dll
        NativeLibrary.addSearchPath("libvlccore", vlcDir);                      // libvlccore.dll
        System.setProperty("jna.library.path", vlcDir); // helpful for debug/other loaders

        // You can keep discovery, but it's no longer necessary after addSearchPath
        // new NativeDiscovery().discover();

        vlcFactory = new MediaPlayerFactory(
                "--no-video-title-show",
                "-vvv"
        );

        vlcPlayer = vlcFactory.mediaPlayers().newEmbeddedMediaPlayer();
        vlcPlayer.videoSurface().set(new ImageViewVideoSurface(vlcView));

        vlcPlayer.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override public void playing(MediaPlayer mediaPlayer) {
                mediaPlayer.submit(() -> {
                    if (pauseOnFirstPlaying) {
                        pauseOnFirstPlaying = false;

                        long target = Math.max(0, initialPauseMs);
                        mediaPlayer.controls().setTime(target);
                        mediaPlayer.controls().setPause(true);

                        Platform.runLater(() -> {
                            desiredMs = target;
                            scrubOverlay.setVisible(true);
                            requestScrubFrame(target);
                            updatePlayButtonState();
                        });
                        return;
                    }

                    if (seekOnNextPlayingEvent >= 0) {
                        long target = seekOnNextPlayingEvent;
                        boolean keepPlaying = keepPlayingAfterSeek;

                        seekOnNextPlayingEvent = -1;
                        keepPlayingAfterSeek = false;

                        mediaPlayer.controls().setTime(target);
                        if (!keepPlaying) mediaPlayer.controls().setPause(true);

                        Platform.runLater(() -> {
                            desiredMs = target;
                            updatePlayButtonState();
                        });
                    }
                });

                Platform.runLater(() -> updatePlayButtonState());
            }

            @Override public void paused(MediaPlayer mediaPlayer) {
                Platform.runLater(() -> updatePlayButtonState());
            }

            @Override public void finished(MediaPlayer mediaPlayer) {
                Platform.runLater(() -> updatePlayButtonState());
            }

            @Override public void error(MediaPlayer mediaPlayer) {
                Platform.runLater(() -> {
                    clipInfo.setText("⚠ VLC error playing media");
                    updatePlayButtonState();
                });
            }
        });
    }


    private void startUiTimer() {
        if (uiTimer != null) return;

        uiTimer = new Timeline(new KeyFrame(Duration.millis(UI_TICK_MS), e -> uiTick()));
        uiTimer.setCycleCount(Animation.INDEFINITE);
        uiTimer.play();
    }

    private void uiTick() {
        if (vlcPlayer == null || currentFile == null) return;

        boolean playing = vlcPlayer.status().isPlaying();

        // Update length lazily
        if (mediaLengthMs <= 1) {
            long len = vlcPlayer.status().length();
            if (len > 0) {
                mediaLengthMs = len;
                progress.setMax(Math.max(1, len));
            }
        }

        long nowMs;
        if (playing) {
            nowMs = vlcPlayer.status().time();
            if (nowMs >= 0) desiredMs = nowMs;
        } else {
            nowMs = desiredMs;
        }

        if (!progress.isValueChanging()) progress.setValue(nowMs);

        long sec = nowMs / 1000;
        if (sec != lastShownSec) {
            lastShownSec = sec;
            timeLabel.setText(formatTimeMs(nowMs));
        }

        // Hide scrub overlay once VLC reaches the target during playback
        if (playing && scrubOverlay.isVisible() && hideScrubWhenNearMs >= 0) {
            if (Math.abs(nowMs - hideScrubWhenNearMs) <= 140) {
                scrubOverlay.setVisible(false);
                hideScrubWhenNearMs = -1;
            }
        }

        if (onTime != null) onTime.accept(desiredMs);

        updatePlayButtonState();
    }

    private void togglePlay() {
        if (vlcPlayer == null || currentFile == null) return;

        if (vlcPlayer.status().isPlaying()) {
            pause();
            return;
        }

        // Start playing from desiredMs.
        // Set time immediately AND also on next playing event (covers edge cases where setTime is ignored before playing).
        long target = Math.max(0, desiredMs);
        try { vlcPlayer.controls().setTime(target); } catch (Exception ignored) {}

        seekOnNextPlayingEvent = target;
        keepPlayingAfterSeek = true;

        // Keep scrub overlay up until VLC catches up (prevents a "flash" back to old frame)
        hideScrubWhenNearMs = target;

        vlcPlayer.controls().play();
        updatePlayButtonState();
    }

    public void play() {
        if (vlcPlayer == null || currentFile == null) return;
        if (!vlcPlayer.status().isPlaying()) {
            togglePlay();
        }
    }

    public void pause() {
        if (vlcPlayer == null || currentFile == null) return;
        try { vlcPlayer.controls().setPause(true); } catch (Exception ignored) {}
        // When paused, show the accurate frame overlay
        scrubOverlay.setVisible(true);
        requestScrubFrame(desiredMs);
        updatePlayButtonState();
    }

    private void updatePlayButtonState() {
        boolean playing = isPlaying();
        playBtn.setText(playing ? "⏸" : "▶");
    }

    private void updateEmptyState() {
        boolean empty = (currentFile == null);
        emptyHint.setVisible(empty);
        bigPlay.setVisible(empty);
        timeLabel.setVisible(true);
        progress.setDisable(empty);
    }

    public void setInfo(String text) { clipInfo.setText(text); }

    /**
     * Hybrid seek logic:
     * - If playing → seek VLC directly
     * - If paused  → do NOT seek VLC repeatedly; use FFmpeg frame overlay instead
     */
    public void seekTo(long ms) {
        if (currentFile == null) return;

        long max = (mediaLengthMs > 1 ? mediaLengthMs : ms);
        long target = Math.max(0, Math.min(ms, max));
        desiredMs = target;

        if (!progress.isValueChanging()) progress.setValue(target);
        timeLabel.setText(formatTimeMs(target));

        if (isPlaying()) {
            scrubOverlay.setVisible(false);
            hideScrubWhenNearMs = target;
            try { vlcPlayer.controls().setTime(target); } catch (Exception ignored) {}
        } else {
            scrubOverlay.setVisible(true);
            requestScrubFrame(target);
        }
    }

    public void seekWhenReady(long ms) {
        seekTo(ms);
    }

    private void seekToUser(long ms) {
        if (onUserSeek != null) onUserSeek.accept(ms);
        seekTo(ms);
    }

    public void clear() {
        currentFile = null;
        desiredMs = 0;
        mediaLengthMs = 1;

        pauseOnFirstPlaying = false;
        seekOnNextPlayingEvent = -1;
        keepPlayingAfterSeek = false;
        hideScrubWhenNearMs = -1;

        // Invalidate scrub work and clear the overlay
        scrubRequestId.incrementAndGet();
        scrubOverlay.setVisible(false);
        scrubOverlay.setImage(null);

        if (vlcPlayer != null) {
            try { vlcPlayer.controls().stop(); } catch (Exception ignored) {}
            try { vlcPlayer.release(); } catch (Exception ignored) {}
            vlcPlayer = null;
        }
        if (vlcFactory != null) {
            try { vlcFactory.release(); } catch (Exception ignored) {}
            vlcFactory = null;
        }

        setInfo("");
        timeLabel.setText("00:00:00");

        progress.setDisable(true);
        progress.setMin(0);
        progress.setMax(1);
        progress.setValue(0);

        updateEmptyState();
    }

    public void setOnUserSeek(LongConsumer h) { this.onUserSeek = h; }
    public void setOnTime(LongConsumer h) { this.onTime = h; }

    public boolean isPlaying() {
        return vlcPlayer != null && vlcPlayer.status().isPlaying();
    }

    /**
     * When paused, VLC's internal clock might not move (because we avoid seeking VLC repeatedly),
     * so return desiredMs which matches the timeline playhead.
     */
    public long getCurrentMs() {
        if (vlcPlayer == null) return 0;
        return isPlaying() ? Math.max(0, vlcPlayer.status().time()) : desiredMs;
    }

    public BorderPane getRoot() { return root; }

    // -----------------------
    // FFmpeg scrub frame logic
    // -----------------------

    private void requestScrubFrame(long ms) {
        if (currentFile == null) return;

        // Clear queued requests so we don't lag behind
        scrubExec.getQueue().clear();

        final long requestId = scrubRequestId.incrementAndGet();
        final File file = currentFile;
        final long ts = ms;

        scrubExec.execute(() -> {
            byte[] imgBytes = extractFrameJpeg(file, ts);
            if (imgBytes == null || imgBytes.length == 0) return;

            Platform.runLater(() -> {
                if (requestId != scrubRequestId.get()) return; // stale
                if (currentFile == null || !currentFile.equals(file)) return;

                Image img = new Image(new ByteArrayInputStream(imgBytes), 0, 0, true, true);
                scrubOverlay.setImage(img);
            });
        });
    }

    private byte[] extractFrameJpeg(File file, long ms) {
        try {
            // Two-step seek:
            //  - fast keyframe seek with -ss BEFORE -i
            //  - refine with -ss AFTER -i for frame accuracy
            long preMs = Math.max(0, ms - 4000);
            long refineMs = ms - preMs;

            String preTs = msToTimestamp(preMs);
            String refineTs = msToTimestamp(refineMs);

            ProcessBuilder pb = new ProcessBuilder(
                    ffmpeg(),
                    "-hide_banner", "-loglevel", "error",
                    "-ss", preTs,
                    "-i", file.getAbsolutePath(),
                    "-ss", refineTs,
                    "-frames:v", "1",
                    "-an", "-sn", "-dn",
                    "-vf", "scale=1280:-2:flags=fast_bilinear",
                    "-f", "image2pipe",
                    "-vcodec", "mjpeg",
                    "-q:v", "3",
                    "-"
            );

            Process p = pb.start();

            // Drain stderr to avoid deadlocks
            Thread errThread = new Thread(() -> {
                try (InputStream es = p.getErrorStream()) {
                    es.transferTo(new ByteArrayOutputStream());
                } catch (Exception ignored) {}
            }, "ffmpeg-scrub-stderr");
            errThread.setDaemon(true);
            errThread.start();

            byte[] outBytes;
            try (InputStream in = p.getInputStream()) {
                outBytes = in.readAllBytes();
            }

            boolean ok = p.waitFor(6, TimeUnit.SECONDS);
            if (!ok) {
                p.destroyForcibly();
                return null;
            }

            if (p.exitValue() != 0) return null;
            return outBytes;

        } catch (Exception ignored) {
            return null;
        }
    }

    private static String ffmpeg() {
        return System.getenv().getOrDefault("FFMPEG_PATH", "ffmpeg");
    }

    private static String msToTimestamp(long ms) {
        double s = ms / 1000.0;
        return String.format(Locale.US, "%.3f", s);
    }

    private static String formatTimeMs(long ms) {
        long s = ms / 1000;
        long hh = s / 3600;
        long mm = (s % 3600) / 60;
        long ss = s % 60;
        return String.format("%02d:%02d:%02d", hh, mm, ss);
    }
}
