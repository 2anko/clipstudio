package videocutter.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.DoubleConsumer;

public class FfmpegService {
    public static class Meta { public long durationMs; public int width; public int height; }

    /** A timeline segment: take [startMs,endMs] from input. */
    public static class Segment {
        public final Path input;
        public final long startMs;
        public final long endMs;
        public Segment(Path input, long startMs, long endMs) {
            this.input = input;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    private static class VideoInfo {
        int width;
        int height;
        double fps;
        boolean hasAudio;
        long durationMs;
    }

    private String ffmpeg() { return System.getenv().getOrDefault("FFMPEG_PATH", "ffmpeg"); }
    private String ffprobe() { return System.getenv().getOrDefault("FFPROBE_PATH", "ffprobe"); }

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
            p.waitFor();

            Meta m = new Meta();
            Matcher d = Pattern.compile("duration=([0-9]+\\.[0-9]+)").matcher(out);
            if (d.find()) m.durationMs = (long) (Double.parseDouble(d.group(1)) * 1000);

            Matcher w = Pattern.compile("width=([0-9]+)").matcher(out);
            Matcher h = Pattern.compile("height=([0-9]+)").matcher(out);
            if (w.find()) m.width = Integer.parseInt(w.group(1));
            if (h.find()) m.height = Integer.parseInt(h.group(1));

            if (m.durationMs == 0) m.durationMs = 1;
            return m;
        } catch (Exception e) {
            throw new RuntimeException("ffprobe failed: " + e.getMessage(), e);
        }
    }

    /** True if ffmpeg exposes the NVIDIA NVENC encoder (h264_nvenc). */
    public boolean hasNvenc() {
        if (nvencAvailable != null) return nvencAvailable;
        try {
            Process p = new ProcessBuilder(
                    ffmpeg(), "-hide_banner", "-encoders"
            ).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            nvencAvailable = out.contains("h264_nvenc");
        } catch (Exception e) {
            nvencAvailable = false;
        }
        return nvencAvailable;
    }

    /** Stream-copy trim into the provided output path. (Fast, exact ONLY if on keyframes.) */
    public void cutCopyTo(Path input, long startMs, long endMs, Path out) throws Exception {
        long durMs = Math.max(0, endMs - startMs);
        runFFmpeg(List.of(
                "ffmpeg", "-y",
                "-hide_banner", "-loglevel", "error",
                "-ss", msToTs(startMs),
                "-t",  msToTs(durMs),
                "-i", input.toString(),
                "-c", "copy",
                "-avoid_negative_ts", "make_zero",
                "-reset_timestamps", "1",
                out.toString()
        ));
    }

    /** Exact trim by re-encoding video (NVENC if available). */
    public void cutExactTo(Path input, long startMs, long endMs, Path out) throws Exception {
        long durMs = Math.max(0, endMs - startMs);

        List<String> common = List.of(
                "ffmpeg", "-y",
                "-hide_banner", "-loglevel", "error",
                "-ss", msToTs(startMs),
                "-t",  msToTs(durMs),
                "-i", input.toString(),
                "-map", "0:v:0", "-map", "0:a?",
                "-movflags", "+faststart",
                out.toString()
        );

        List<List<String>> candidates = new ArrayList<>();

        if (hasNvenc()) {
            candidates.add(merge(
                    List.of("-c:v", "h264_nvenc", "-preset", "p4", "-rc", "vbr", "-cq", "19", "-b:v", "0",
                            "-c:a", "copy",
                            "-pix_fmt", "yuv420p"),
                    common
            ));
            candidates.add(merge(
                    List.of("-c:v", "h264_nvenc", "-preset", "p4", "-rc", "vbr", "-cq", "19", "-b:v", "0",
                            "-c:a", "aac", "-b:a", "192k",
                            "-pix_fmt", "yuv420p"),
                    common
            ));
        }

        candidates.add(merge(
                List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "18",
                        "-c:a", "copy",
                        "-pix_fmt", "yuv420p"),
                common
        ));
        candidates.add(merge(
                List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "18",
                        "-c:a", "aac", "-b:a", "192k",
                        "-pix_fmt", "yuv420p"),
                common
        ));

        runFFmpegWithFallback(candidates);
    }

    // ---------- NEW: Export timeline (smart-copy if possible, otherwise render exact) ----------

    public void exportTimeline(List<Segment> segs, Path output) throws Exception {
        exportTimeline(segs, output, null);
    }

    public void exportTimeline(List<Segment> segs, Path output, DoubleConsumer onProgress) throws Exception {
        if (segs == null || segs.isEmpty()) throw new IllegalArgumentException("No segments to export.");

        long totalOutMs = 0;
        for (Segment s : segs) totalOutMs += Math.max(0, s.endMs - s.startMs);
        if (totalOutMs <= 0) totalOutMs = 1;

        if (onProgress != null) onProgress.accept(0.0);

        if (tryExportSmartCopy(segs, output, totalOutMs, onProgress)) {
            if (onProgress != null) onProgress.accept(1.0);
            return;
        }

        exportTimelineRender(segs, output, totalOutMs, onProgress);
        if (onProgress != null) onProgress.accept(1.0);
    }

    /**
     * Smart-copy export (LOSSLESS + FAST) conditions:
     * - all segments are from the SAME input file
     * - all cut boundaries land on KEYFRAMES (within tolerance)
     * If true, we can stream-copy each segment and concat with -c copy (exact + no quality loss).
     */
    private boolean tryExportSmartCopy(List<Segment> segs, Path output, long totalOutMs, DoubleConsumer onProgress) throws Exception {

        Path first = segs.get(0).input.toAbsolutePath().normalize();
        for (Segment s : segs) {
            if (!s.input.toAbsolutePath().normalize().equals(first)) return false;
        }

        VideoInfo info = probeVideoInfo(first);
        long total = info.durationMs > 0 ? info.durationMs : probe(first).durationMs;

        // Keyframe tolerance (seconds). 0.02 ~= 20ms.
        double tol = 0.02;

        for (Segment s : segs) {
            if (s.startMs > 0 && !isKeyframeNear(first, s.startMs, tol)) return false;
            if (s.endMs < total - 1 && !isKeyframeNear(first, s.endMs, tol)) return false;
        }

        // All boundaries are keyframes -> safe exact smart-copy
        List<Path> parts = new ArrayList<>();
        long doneMs = 0;
        try {
            for (int i = 0; i < segs.size(); i++) {
                Segment s = segs.get(i);
                Path tmp = TempFiles.tmp("seg-copy-" + i + "-", ".mp4");
                cutCopyTo(first, s.startMs, s.endMs, tmp);
                parts.add(tmp);

                doneMs += Math.max(0, s.endMs - s.startMs);
                if (onProgress != null) onProgress.accept(Math.min(0.98, doneMs / (double) totalOutMs));
            }
            concat(parts, output);
            return true;
        } finally {
            for (Path p : parts) {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Exact timeline render (encode once).
     * - Uses trim/atrim + concat filter.
     * - Normalizes to the first segment's resolution + fps.
     * - If a segment has no audio, inserts silence for that segment.
     */
    private void exportTimelineRender(List<Segment> segs, Path output, long totalOutMs, DoubleConsumer onProgress) throws Exception {
        VideoInfo base = probeVideoInfo(segs.get(0).input);

        int tw = base.width > 0 ? base.width : 1920;
        int th = base.height > 0 ? base.height : 1080;
        double fps = base.fps > 1 ? base.fps : 60.0;

        StringBuilder fc = new StringBuilder();

        for (int i = 0; i < segs.size(); i++) {
            Segment s = segs.get(i);
            VideoInfo vi = probeVideoInfo(s.input);

            double ss = s.startMs / 1000.0;
            double ee = s.endMs / 1000.0;
            double dur = Math.max(0.001, (s.endMs - s.startMs) / 1000.0);

            // Video chain
            fc.append(String.format(java.util.Locale.US,
                    "[%d:v]trim=start=%.6f:end=%.6f,setpts=PTS-STARTPTS," +
                            "scale=%d:%d:force_original_aspect_ratio=decrease," +
                            "pad=%d:%d:(ow-iw)/2:(oh-ih)/2,setsar=1," +
                            "fps=fps=%.3f,format=yuv420p[v%d];",
                    i, ss, ee, tw, th, tw, th, fps, i));

            // Audio chain (real audio or silence)
            if (vi.hasAudio) {
                fc.append(String.format(java.util.Locale.US,
                        "[%d:a]atrim=start=%.6f:end=%.6f,asetpts=PTS-STARTPTS,aresample=48000[a%d];",
                        i, ss, ee, i));
            } else {
                fc.append(String.format(java.util.Locale.US,
                        "anullsrc=r=48000:cl=stereo,atrim=0:%.6f,asetpts=PTS-STARTPTS[a%d];",
                        dur, i));
            }
        }

        // Concat
        for (int i = 0; i < segs.size(); i++) {
            fc.append("[v").append(i).append("][a").append(i).append("]");
        }
        fc.append("concat=n=").append(segs.size()).append(":v=1:a=1[vout][aout]");

        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg"); cmd.add("-y");
        cmd.add("-hide_banner"); cmd.add("-loglevel"); cmd.add("error");

        // Inputs
        for (Segment s : segs) {
            cmd.add("-i"); cmd.add(s.input.toString());
        }

        cmd.add("-filter_complex"); cmd.add(fc.toString());
        cmd.add("-map"); cmd.add("[vout]");
        cmd.add("-map"); cmd.add("[aout]");
        cmd.add("-movflags"); cmd.add("+faststart");

        // Encoder
        if (hasNvenc()) {
            cmd.addAll(List.of(
                    "-c:v", "h264_nvenc",
                    "-preset", "p4",
                    "-rc", "vbr",
                    "-cq", "19",
                    "-b:v", "0"
            ));
            System.out.println("[export] Render uses NVENC (h264_nvenc)");
        } else {
            cmd.addAll(List.of(
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-crf", "18"
            ));
            System.out.println("[export] Render uses CPU (libx264)");
        }

        // Standardize audio so export always works
        cmd.addAll(List.of("-c:a", "aac", "-b:a", "192k"));

        cmd.add(output.toString());

        runFFmpegWithProgress(cmd, totalOutMs, onProgress);
    }

    private void runFFmpegWithProgress(List<String> args, long totalOutMs, DoubleConsumer onProgress)
            throws IOException, InterruptedException {

        if (!args.isEmpty() && "ffmpeg".equals(args.get(0))) {
            args = new ArrayList<>(args);
            args.set(0, ffmpeg());
        }

        // Insert progress flags right before the output path (last arg).
        List<String> cmd = new ArrayList<>(args);
        int outIdx = Math.max(0, cmd.size() - 1);
        cmd.add(outIdx, "-progress");
        cmd.add(outIdx + 1, "pipe:1");
        cmd.add(outIdx + 2, "-nostats");

        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();

        StringBuilder all = new StringBuilder();
        long lastMs = -1;

        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                all.append(line).append('\n');

                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();

                long outMs = -1;
                if ("out_time_ms".equals(key) || "out_time_us".equals(key)) {
                    try {
                        long raw = Long.parseLong(val);
                        // Some builds report microseconds even for out_time_ms; this heuristic fixes it.
                        if (raw > totalOutMs * 1000L) outMs = raw / 1000L;
                        else outMs = raw;
                    } catch (NumberFormatException ignored) {}
                } else if ("out_time".equals(key)) {
                    outMs = parseTimestampToMs(val);
                } else {
                    continue;
                }

                if (outMs >= 0 && totalOutMs > 0 && outMs > lastMs) {
                    lastMs = outMs;
                    double p01 = Math.max(0.0, Math.min(1.0, outMs / (double) totalOutMs));
                    if (onProgress != null) onProgress.accept(p01);
                }
            }
        }

        int code = p.waitFor();
        if (code != 0) {
            throw new RuntimeException(
                    "ffmpeg failed (exit=" + code + ")\n" +
                            String.join(" ", cmd) + "\n" +
                            all
            );
        }
    }

    private static long parseTimestampToMs(String ts) {
        // ts like 00:00:12.345678
        try {
            String[] parts = ts.split(":");
            if (parts.length != 3) return -1;
            int hh = Integer.parseInt(parts[0]);
            int mm = Integer.parseInt(parts[1]);
            double ss = Double.parseDouble(parts[2]);
            return (long) ((hh * 3600.0 + mm * 60.0 + ss) * 1000.0);
        } catch (Exception e) {
            return -1;
        }
    }


    private VideoInfo probeVideoInfo(Path file) {
        VideoInfo vi = new VideoInfo();
        Meta m = probe(file);
        vi.width = m.width;
        vi.height = m.height;
        vi.durationMs = m.durationMs;

        vi.hasAudio = hasAudioStream(file);
        vi.fps = probeFps(file);
        return vi;
    }

    private boolean hasAudioStream(Path file) {
        try {
            Process p = new ProcessBuilder(
                    ffprobe(),
                    "-v", "error",
                    "-select_streams", "a:0",
                    "-show_entries", "stream=index",
                    "-of", "csv=p=0",
                    file.toString()
            ).redirectErrorStream(true).start();

            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            return !out.isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    private double probeFps(Path file) {
        try {
            Process p = new ProcessBuilder(
                    ffprobe(),
                    "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=avg_frame_rate",
                    "-of", "default=noprint_wrappers=1:nokey=0",
                    file.toString()
            ).redirectErrorStream(true).start();

            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();

            Matcher r = Pattern.compile("avg_frame_rate=([0-9]+)/([0-9]+)").matcher(out);
            if (r.find()) {
                double num = Double.parseDouble(r.group(1));
                double den = Double.parseDouble(r.group(2));
                if (den != 0) return num / den;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private boolean isKeyframeNear(Path file, long ms, double tolSec) {
        double t = ms / 1000.0;
        double start = Math.max(0, t - tolSec);
        double dur = tolSec * 2 + 0.10;

        String interval = String.format(java.util.Locale.US, "%.3f%%+%.3f", start, dur);

        try {
            Process p = new ProcessBuilder(
                    ffprobe(),
                    "-v", "error",
                    "-select_streams", "v:0",
                    "-read_intervals", interval,
                    "-show_frames",
                    "-show_entries", "frame=key_frame,best_effort_timestamp_time",
                    "-of", "csv=p=0",
                    file.toString()
            ).redirectErrorStream(true).start();

            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();

            // Lines look like: "1,12.345678"
            for (String line : out.split("\\R")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",");
                if (parts.length < 2) continue;
                int kf = Integer.parseInt(parts[0].trim());
                if (kf != 1) continue;
                double ts = Double.parseDouble(parts[1].trim());
                if (Math.abs(ts - t) <= tolSec) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // Concat using demuxer; requires a list file (works when clips have matching stream layouts).
    public void concat(List<Path> inputs, Path output) {
        try {
            Path list = TempFiles.tmp("concat-", ".txt");
            List<String> lines = new ArrayList<>();
            for (Path p : inputs) lines.add("file '" + p.toAbsolutePath() + "'");
            Files.write(list, lines, StandardCharsets.UTF_8);

            Process pr = new ProcessBuilder(
                    ffmpeg(), "-y",
                    "-f", "concat", "-safe", "0",
                    "-i", list.toString(),
                    "-c", "copy",
                    output.toString()
            ).inheritIO().start();

            pr.waitFor();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void runFFmpeg(List<String> args) throws IOException, InterruptedException {
        if (!args.isEmpty() && "ffmpeg".equals(args.get(0))) {
            args = new ArrayList<>(args);
            args.set(0, ffmpeg());
        }

        Process p = new ProcessBuilder(args)
                .redirectErrorStream(true)
                .start();

        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new RuntimeException(
                    "ffmpeg failed (exit=" + code + ")\n" +
                            String.join(" ", args) + "\n" + out
            );
        }
    }

    private void runFFmpegWithFallback(List<List<String>> candidates) throws Exception {
        RuntimeException last = null;
        for (List<String> cmd : candidates) {
            try {
                runFFmpeg(cmd);
                return;
            } catch (RuntimeException e) {
                last = e;
            }
        }
        if (last != null) throw last;
        throw new RuntimeException("ffmpeg failed (no candidates)");
    }

    private static List<String> merge(List<String> beforeOut, List<String> common) {
        List<String> out = new ArrayList<>();
        out.addAll(common.subList(0, common.size() - 1));
        out.addAll(beforeOut);
        out.add(common.get(common.size() - 1));
        return out;
    }

    private String msToTs(long ms) {
        double s = ms / 1000.0;
        return String.format(java.util.Locale.US, "%.3f", s);
    }
}
