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
    public void splitClipAt(TimelineClip clip, long cutMs) {
        int idx = clips.indexOf(clip);
        if (idx < 0) return;

        long s = clip.startMs();
        long e = clip.endMs();

        if (cutMs <= s + 100 || cutMs >= e - 100) return; // prevent tiny clips

        TimelineClip left = new TimelineClip(clip.assetId(), s, cutMs);
        TimelineClip right = new TimelineClip(clip.assetId(), cutMs, e);

        clips.set(idx, left);
        clips.add(idx + 1, right);
    }

    public void clear() { clips.clear(); }

    public void replaceClipWithSplit(TimelineClip oldClip, TimelineClip left, TimelineClip right) {
        int i = clips.indexOf(oldClip);
        if (i < 0) return;
        clips.remove(i);
        clips.add(i, left);
        clips.add(i + 1, right);
    }

}
