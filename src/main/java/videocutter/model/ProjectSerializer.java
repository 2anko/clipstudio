package videocutter.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Saves and loads a Project's clip list to/from a small JSON .framecut file.
 *
 * Format:
 * {
 *   "version": 1,
 *   "clips": [
 *     { "assetId": 3, "startMs": 5000, "endMs": 12000 },
 *     ...
 *   ]
 * }
 *
 * Asset files live in the media vault (VideoRepository) and are looked up by id
 * at load time, so the save file stays tiny.
 */
public class ProjectSerializer {

    private static final Pattern CLIP_PATTERN = Pattern.compile(
            "\"assetId\"\\s*:\\s*(\\d+)\\s*,\\s*\"startMs\"\\s*:\\s*(\\d+)\\s*,\\s*\"endMs\"\\s*:\\s*(\\d+)"
    );

    public record ClipData(long assetId, long startMs, long endMs) {}

    // ---- Save ----

    public static void save(Project project, Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"version\": 1,\n  \"clips\": [\n");

        List<TimelineClip> clips = project.clips();
        for (int i = 0; i < clips.size(); i++) {
            TimelineClip c = clips.get(i);
            sb.append("    { \"assetId\": ").append(c.assetId())
                    .append(", \"startMs\": ").append(c.startMs())
                    .append(", \"endMs\": ").append(c.endMs())
                    .append(" }");
            if (i < clips.size() - 1) sb.append(",");
            sb.append("\n");
        }

        sb.append("  ]\n}\n");
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    // ---- Load ----

    public static List<ClipData> load(Path path) throws IOException {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        List<ClipData> out = new ArrayList<>();
        Matcher m = CLIP_PATTERN.matcher(json);
        while (m.find()) {
            long assetId = Long.parseLong(m.group(1));
            long startMs = Long.parseLong(m.group(2));
            long endMs   = Long.parseLong(m.group(3));
            out.add(new ClipData(assetId, startMs, endMs));
        }
        return out;
    }
}