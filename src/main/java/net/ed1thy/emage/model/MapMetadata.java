package net.ed1thy.emage.model;

import org.jetbrains.annotations.NotNull;
import java.util.UUID;

public record MapMetadata(
        int syncGroupID,
        @NotNull UUID creatorUUID,
        @NotNull String sourceUrl,
        int columns,
        int rows,
        int totalFrames,
        int delayMs
) {
    public boolean isAnimated() {
        return totalFrames > 1 && delayMs > 0;
    }
}
