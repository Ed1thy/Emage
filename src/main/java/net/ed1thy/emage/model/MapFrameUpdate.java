package net.ed1thy.emage.model;

public record MapFrameUpdate(DeltaFrame[] parts) {

    public void freeMemory() {
        for (DeltaFrame df : parts) {
            df.freeMemory();
        }
    }
}