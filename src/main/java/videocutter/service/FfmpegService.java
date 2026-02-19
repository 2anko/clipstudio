package videocutter.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import videocutter.model.Resolution;
import videocutter.model.VideoAsset;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FfmpegService {
    private static final Logger LOG = LoggerFactory.getLogger(FfmpegService.class);

    public record Segment(Path input, long startMs, long endMs) {
    }

    private String ffmpeg() {
        return System.getenv().getOrDefault("FFMPEG_PATH", "ffmpeg");
    }

    private String ffprobe() {
        return System.getenv().getOrDefault("FFPROBE_PATH", "ffprobe");
    }

    // Cache encoder availability so we don't spawn "ffmpeg -encoders" repeatedly.
    private Boolean nvencAvailable;

    public Meta probe(Path file) {
        try {
            Process p = new ProcessBuilder(
                    ffprobe(),
                    "-v", "error",
                    "-show_entries", "format=duration:stream=width,height",
                    "-of", "default=noprint_wrappers=1:nokey=0",
                    file.toString()
            ).redirectErrorStream(true).start();

            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) {
                LOG.warn("ffprobe failed for: {}\n{}", file, out);
                return new Meta(1, 0, 0);
            }

            long durationMs = 1;
            int width = 0;
            int height = 0;

            Matcher d = Pattern.compile("duration=([0-9]+\\.?[0-9]*)").matcher(out);
            if (d.find()) {
                durationMs = (long) (Double.parseDouble(d.group(1)) * 1000);
            }

            Matcher w = Pattern.compile("width=([0-9]+)").matcher(out);
            if (w.find()) {
                width = Integer.parseInt(w.group(1));
            }

            Matcher h = Pattern.compile("height=([0-9]+)").matcher(out);
            if (h.find()) {
                height = Integer.parseInt(h.group(1));
            }
            return new Meta(durationMs, width, height);
        } catch (Exception e) {
            LOG.error("ffprobe failed", e);
            return new Meta(1, 0, 0);
        }
    }


    /**
     * Probes the frame rate of the first video stream in the file.
     * ffprobe returns r_frame_rate as a fraction e.g. "30/1" or "24000/1001".
     * Falls back to 30.0 if probing fails or the file has no video stream.
     */
    public double probeFps(Path file) {
        try {
            Process p = new ProcessBuilder(
                    ffprobe(),
                    "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=r_frame_rate",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    file.toString()
            ).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            if (out.contains("/")) {
                String[] parts = out.split("/");
                double num = Double.parseDouble(parts[0].trim());
                double den = Double.parseDouble(parts[1].trim());
                if (den > 0 && num > 0) return num / den;
            }
        } catch (Exception e) {
            LOG.warn("probeFps failed for {}, falling back to 30", file);
        }
        return 30.0;
    }

    public boolean hasNvenc() {
        if (nvencAvailable != null) {
            return nvencAvailable;
        }
        try {
            Process p = new ProcessBuilder(ffmpeg(), "-hide_banner", "-encoders").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            nvencAvailable = out.contains("h264_nvenc");
        } catch (Exception e) {
            LOG.warn("Could not determine NVENC availability", e);
            nvencAvailable = false;
        }
        return nvencAvailable;
    }

    public void cutCopyTo(Path input, long startMs, long endMs, Path out) throws IOException {
        long durMs = Math.max(0, endMs - startMs);
        runFFmpeg(List.of(
                "ffmpeg", "-y",
                "-hide_banner", "-loglevel", "error",
                "-ss", msToTs(startMs),
                "-t", msToTs(durMs),
                "-i", input.toString(),
                "-c", "copy",
                "-map", "0",
                "-avoid_negative_ts", "make_zero",
                "-reset_timestamps", "1",
                out.toString()
        ), null);
    }

    public void exportTimeline(List<Segment> segs, Resolution resolution, Path output, Consumer<Double> onProgress) throws IOException {
        Objects.requireNonNull(segs);
        Objects.requireNonNull(resolution);
        Objects.requireNonNull(output);
        if (segs.isEmpty()) {
            throw new IllegalArgumentException("No segments to export.");
        }

        // Use a single, robust render path. Smart-copy was brittle and offered minor speedups
        // for a narrow set of cases. A modern encoder is fast
        // enough that a simple re-encode is a better default.
        exportTimelineRender(segs, resolution, output, onProgress);
    }

    private void exportTimelineRender(List<Segment> segs, Resolution resolution, Path output, Consumer<Double> onProgress) throws IOException {
        int tw = resolution.width();
        int th = resolution.height();

        // Probe each segment's FPS and pick the most common one as the export target.
        // The fps filter in each chain converts clips that differ, so concat always gets
        // a consistent frame rate regardless of mixed-fps source material.
        java.util.Map<Double, Long> fpsVotes = new java.util.LinkedHashMap<>();
        for (Segment s : segs) {
            double f = probeFps(s.input);
            fpsVotes.merge(f, 1L, Long::sum);
        }
        double fps = fpsVotes.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey)
                .orElse(30.0);
        LOG.info("Export target FPS: {}", fps);

        StringBuilder fc = new StringBuilder();
        long totalDurationMs = 0;

        for (int i = 0; i < segs.size(); i++) {
            Segment s = segs.get(i);
            long durMs = Math.max(0, s.endMs - s.startMs);
            totalDurationMs += durMs;

            double startSec = s.startMs / 1000.0;
            double endSec = s.endMs / 1000.0;
            double durSec  = durMs / 1000.0;

            // Video chain — fps filter normalises each clip to the export target rate.
            fc.append(String.format(java.util.Locale.US,
                    "[%d:v]trim=start=%.6f:end=%.6f,setpts=PTS-STARTPTS," +
                            "scale=%d:%d:force_original_aspect_ratio=decrease," +
                            "pad=%d:%d:(ow-iw)/2:(oh-ih)/2,setsar=1," +
                            "fps=fps=%.6f,format=yuv420p[v%d];",
                    i, startSec, endSec, tw, th, tw, th, fps, i));

            // Audio chain — fall back to silence if the file has no audio stream.
            if (hasAudio(s.input)) {
                fc.append(String.format(java.util.Locale.US,
                        "[%d:a]atrim=start=%.6f:end=%.6f,asetpts=PTS-STARTPTS[a%d];",
                        i, startSec, endSec, i));
            } else {
                fc.append(String.format(java.util.Locale.US,
                        "aevalsrc=0:channel_layout=stereo:sample_rate=44100:duration=%.6f[a%d];",
                        durSec, i));
            }
        }

        // Concat video and audio streams
        for (int i = 0; i < segs.size(); i++) {
            fc.append("[v").append(i).append("][a").append(i).append("]");
        }
        fc.append("concat=n=").append(segs.size()).append(":v=1:a=1[vout][aout]");


        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.add("-y");
        cmd.add("-hide_banner");

        // Inputs
        for (Segment s : segs) {
            cmd.add("-i");
            cmd.add(s.input.toString());
        }

        cmd.add("-filter_complex");
        cmd.add(fc.toString());
        cmd.add("-map");
        cmd.add("[vout]");
        cmd.add("-map");
        cmd.add("[aout]");
        cmd.add("-movflags");
        cmd.add("+faststart");

        if (hasNvenc()) {
            cmd.addAll(List.of("-c:v", "h264_nvenc", "-preset", "p4", "-rc", "vbr", "-cq", "19", "-b:v", "0"));
            LOG.info("Export render uses NVENC (h264_nvenc)");
        } else {
            cmd.addAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "18"));
            LOG.info("Export render uses CPU (libx264)");
        }

        cmd.addAll(List.of("-c:a", "aac", "-b:a", "192k"));
        cmd.add(output.toString());

        runFFmpeg(cmd, new FfmpegProgressParser(totalDurationMs, onProgress));
    }


    /** Returns true if the file has at least one audio stream. */
    public boolean hasAudio(Path file) {
        try {
            Process p = new ProcessBuilder(
                    ffprobe(),
                    "-v", "error",
                    "-select_streams", "a:0",
                    "-show_entries", "stream=codec_type",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    file.toString()
            ).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            return out.contains("audio");
        } catch (Exception e) {
            LOG.warn("hasAudio probe failed for {}, assuming true", file);
            return true; // safe default: let FFmpeg try and fail loudly if wrong
        }
    }

    private void runFFmpeg(List<String> args, FfmpegProgressParser progress) throws IOException {
        List<String> cmd = new ArrayList<>(args);
        cmd.set(0, ffmpeg()); // Set the correct ffmpeg path

        // Add progress monitoring flags
        if (progress != null) {
            cmd.add(cmd.size() - 1, "-progress");
            cmd.add(cmd.size() - 1, "pipe:1");
            cmd.add(cmd.size() - 1, "-nostats");
        }

        LOG.info("Executing FFmpeg: {}", String.join(" ", cmd));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();

        StringBuilder ffmpegOutput = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                ffmpegOutput.append(line).append(System.lineSeparator());
                if (progress != null) {
                    progress.parseLine(line);
                }
            }
        }

        try {
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                throw new IOException("ffmpeg failed with exit code " + exitCode + ":\n" + ffmpegOutput);
            }
            if (progress != null) {
                progress.onDone();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ffmpeg execution interrupted", e);
        }
    }


    private String msToTs(long ms) {
        return String.format(java.util.Locale.US, "%.3f", ms / 1000.0);
    }
}