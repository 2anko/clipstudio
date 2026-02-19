package videocutter.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FfmpegProgressParser {
    private static final Logger LOG = LoggerFactory.getLogger(FfmpegProgressParser.class);
    private static final Pattern PROGRESS_LINE_RE = Pattern.compile("(frame|out_time_ms|progress|speed|total_size)=\\s*(\\S+)");

    private final long totalDurationMs;
    private final Consumer<Double> onProgress;
    private long lastReportedMs = -1;

    public FfmpegProgressParser(long totalDurationMs, Consumer<Double> onProgress) {
        this.totalDurationMs = totalDurationMs > 0 ? totalDurationMs : 1;
        this.onProgress = onProgress;
    }

    public void parseLine(String line) {
        if (line == null || onProgress == null) {
            return;
        }

        Matcher m = PROGRESS_LINE_RE.matcher(line);
        long currentMs = -1;

        while (m.find()) {
            String key = m.group(1);
            String value = m.group(2);

            if ("out_time_ms".equals(key)) {
                try {
                    currentMs = Long.parseLong(value) / 1000;
                } catch (NumberFormatException e) {
                    LOG.warn("Invalid out_time_ms: {}", value);
                }
                break; // This is the most reliable timestamp
            }
        }

        if (currentMs != -1 && currentMs > lastReportedMs) {
            lastReportedMs = currentMs;
            double progress = Math.max(0.0, Math.min(1.0, (double) currentMs / totalDurationMs));
            onProgress.accept(progress);
        }
    }

    public void onDone() {
        if (onProgress != null) {
            onProgress.accept(1.0);
        }
    }
}
