package videocutter.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Project {
    private final List<TimelineClip> clips = new ArrayList<>();


    public void addClip(TimelineClip c) { clips.add(c); }
    public void removeClip(TimelineClip c) { clips.remove(c); }
    public List<TimelineClip> clips() { return Collections.unmodifiableList(clips); }
    public void removeClipsByAssetIds(java.util.Set<Long> assetIds) {
        clips.removeIf(c -> assetIds.contains(c.assetId()));
    }
}
