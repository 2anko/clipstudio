package videocutter.model;

import java.time.Instant;

public record VideoAsset(long id, String title, long durationMs, int width, int height, Instant createdAt) {
    public String prettyDuration() {
        long s = durationMs / 1000;
        long m = s / 60; s %= 60;
        return String.format("%d:%02d", m, s);
    }
}
