package videocutter.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class FfmpegService {
    public static class Meta { public long durationMs; public int width; public int height; }

    private String ffmpeg() { return System.getenv().getOrDefault("FFMPEG_PATH", "ffmpeg"); }
    private String ffprobe() { return System.getenv().getOrDefault("FFPROBE_PATH", "ffprobe"); }

    public Meta probe(Path file) {
        try {
            Process p = new ProcessBuilder(ffprobe(), "-v", "error", "-show_entries", "format=duration:stream=width,height", "-of", "default=noprint_wrappers=1:nokey=0", file.toString())
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            Meta m = new Meta();
            // duration=123.456
            Matcher d = Pattern.compile("duration=([0-9]+\\.[0-9]+)").matcher(out);
            if (d.find()) m.durationMs = (long) (Double.parseDouble(d.group(1)) * 1000);
            Matcher w = Pattern.compile("width=([0-9]+)").matcher(out);
            Matcher h = Pattern.compile("height=([0-9]+)").matcher(out);
            if (w.find()) m.width = Integer.parseInt(w.group(1));
            if (h.find()) m.height = Integer.parseInt(h.group(1));
            if (m.durationMs == 0) m.durationMs = 1; // avoid zero
            return m;
        } catch (Exception e) { throw new RuntimeException("ffprobe failed: " + e.getMessage(), e); }
    }


    // Cut [start,end] (ms) into a temp mp4 using stream copy when possible.
    public Path cut(Path input, long startMs, long endMs) {
        try {
            Path out = TempFiles.tmp("cut-", ".mp4");
            String ss = toTimestamp(startMs);
            String to = toTimestamp(endMs);
            Process p = new ProcessBuilder(ffmpeg(), "-y",
                    "-ss", ss, "-to", to, "-i", input.toString(),
                    "-c", "copy", out.toString())
                    .inheritIO().start();
            p.waitFor();
            return out;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // Concat using demuxer; requires a list file.
    public void concat(List<Path> inputs, Path output) {
        try {
            Path list = TempFiles.tmp("concat-", ".txt");
            List<String> lines = new ArrayList<>();
            for (Path p : inputs) lines.add("file '" + p.toAbsolutePath() + "'");
            Files.write(list, lines, StandardCharsets.UTF_8);
            Process pr = new ProcessBuilder(ffmpeg(), "-y", "-f", "concat", "-safe", "0", "-i", list.toString(), "-c", "copy", output.toString())
                    .inheritIO().start();
            pr.waitFor();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private String toTimestamp(long ms) {
        long s = ms / 1000; long rem = ms % 1000;
        long hh = s/3600; s%=3600; long mm = s/60; s%=60;
        return String.format("%02d:%02d:%02d.%03d", hh, mm, s, rem);
    }
}