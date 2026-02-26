package net.edithymaster.emage.Render;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SyncGroup {
    public final long syncID;
    public final Set<GifRenderer> renderers = ConcurrentHashMap.newKeySet();
    public final int[] delays;
    public final long totalDuration;
    public final int frameCount;
    public final long[] cumulativeDelays;

    public long startTime = 0;
    public int currentFrame = 0;
    public boolean active = false;

    public SyncGroup(long syncID, List<Integer> delayList) {
        this.syncID = syncID;
        this.frameCount = delayList != null ? delayList.size() : 0;

        if (frameCount == 0) {
            this.delays = new int[0];
            this.cumulativeDelays = new long[0];
            this.totalDuration = 1;
            return;
        }

        this.delays = new int[frameCount];
        this.cumulativeDelays = new long[frameCount];
        long total = 0;
        for (int i = 0; i < frameCount; i++) {
            int delay = delayList.get(i);
            delay = Math.max(20, delay);
            this.delays[i] = delay;
            total += delay;
            this.cumulativeDelays[i] = total;
        }
        this.totalDuration = Math.max(1, total);
    }

    public void start() {
        this.startTime = System.currentTimeMillis();
        this.currentFrame = 0;
        this.active = true;
        markAllDirty();
    }

    public boolean tick(long now, boolean hasPlayers) {
        if (!active || frameCount <= 1) return false;
        if (!hasPlayers) {
            startTime += (now - startTime);
            return false;
        }

        long cyclePosition = (now - startTime) % totalDuration;
        int targetFrame = findFrame(cyclePosition);

        if (targetFrame != currentFrame) {
            currentFrame = targetFrame;
            markAllDirty();
            return true;
        }
        return false;
    }

    private int findFrame(long cyclePosition) {
        int lo = 0, hi = frameCount - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cumulativeDelays[mid] <= cyclePosition) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private void markAllDirty() {
        for (GifRenderer renderer : renderers) {
            if (currentFrame >= 0 && currentFrame < renderer.getFrameCount()) {
                renderer.setNeedsRender(true);
            }
        }
    }

    public int getCurrentFrame() {
        return active ? currentFrame : 0;
    }
}