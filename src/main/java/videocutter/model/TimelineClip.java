package videocutter.model;

public class TimelineClip {
    private final long assetId;
    private long startMs;
    private long endMs;


    public TimelineClip(long assetId, long startMs, long endMs) { this.assetId = assetId; this.startMs = startMs; this.endMs = endMs; }
    public long assetId() { return assetId; }
    public long startMs() { return startMs; }
    public long endMs() { return endMs; }
    public void setStartMs(long v) { startMs = v; }
    public void setEndMs(long v) { endMs = v; }
    public String display() { return "Clip(" + startMs/1000 + "s → " + endMs/1000 + "s)"; }
}
