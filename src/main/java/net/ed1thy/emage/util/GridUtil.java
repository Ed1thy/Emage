package net.ed1thy.emage.util;

import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemFrame;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class GridUtil {

    public record GridData(List<ItemFrame> frames, int columns, int rows) {}

    private record GridVectors(int colDx, int colDy, int colDz, int rowDx, int rowDy, int rowDz) {}

    private static GridVectors getVectors(BlockFace facing) {
        return switch (facing) {
            case NORTH -> new GridVectors(-1, 0, 0, 0, -1, 0);
            case SOUTH -> new GridVectors(1, 0, 0, 0, -1, 0);
            case EAST  -> new GridVectors(0, 0, -1, 0, -1, 0);
            case WEST  -> new GridVectors(0, 0, 1, 0, -1, 0);
            case UP    -> new GridVectors(1, 0, 0, 0, 0, 1);
            case DOWN  -> new GridVectors(1, 0, 0, 0, 0, 1);
            default -> new GridVectors(0, 0, 0, 0, 0, 0);
        };
    }

    @NotNull
    public static ItemFrame findTopLeftFrame(@NotNull ItemFrame clickedFrame) {
        GridVectors v = getVectors(clickedFrame.getFacing());

        int upX = -v.rowDx(), upY = -v.rowDy(), upZ = -v.rowDz();
        int leftX = -v.colDx(), leftY = -v.colDy(), leftZ = -v.colDz();

        if (upX == 0 && upY == 0 && upZ == 0) return clickedFrame;

        ItemFrame current = clickedFrame;
        boolean moved;
        do {
            moved = false;
            ItemFrame above = getFrameAt(current,
                    current.getLocation().getBlockX() + upX,
                    current.getLocation().getBlockY() + upY,
                    current.getLocation().getBlockZ() + upZ);

            if (above != null) {
                current = above;
                moved = true;
                continue;
            }

            ItemFrame left = getFrameAt(current,
                    current.getLocation().getBlockX() + leftX,
                    current.getLocation().getBlockY() + leftY,
                    current.getLocation().getBlockZ() + leftZ);

            if (left != null) {
                current = left;
                moved = true;
            }
        } while (moved);

        return current;
    }

    @Nullable
    public static GridData autoDetectGrid(@NotNull ItemFrame topLeft, int maxLimit) {
        GridVectors v = getVectors(topLeft.getFacing());
        if (v.colDx() == 0 && v.colDy() == 0 && v.colDz() == 0) return null;

        int columns = 0;
        while (columns < maxLimit) {
            ItemFrame frame = getFrameAt(topLeft,
                    topLeft.getLocation().getBlockX() + (columns * v.colDx()),
                    topLeft.getLocation().getBlockY() + (columns * v.colDy()),
                    topLeft.getLocation().getBlockZ() + (columns * v.colDz()));
            if (frame == null) break;
            columns++;
        }

        int rows = 0;
        while (rows < maxLimit) {
            ItemFrame frame = getFrameAt(topLeft,
                    topLeft.getLocation().getBlockX() + (rows * v.rowDx()),
                    topLeft.getLocation().getBlockY() + (rows * v.rowDy()),
                    topLeft.getLocation().getBlockZ() + (rows * v.rowDz()));
            if (frame == null) break;
            rows++;
        }

        if (columns == 0 || rows == 0) return null;

        List<ItemFrame> grid = new ArrayList<>(columns * rows);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                int targetX = topLeft.getLocation().getBlockX() + (c * v.colDx()) + (r * v.rowDx());
                int targetY = topLeft.getLocation().getBlockY() + (c * v.colDy()) + (r * v.rowDy());
                int targetZ = topLeft.getLocation().getBlockZ() + (c * v.colDz()) + (r * v.rowDz());

                ItemFrame foundFrame = getFrameAt(topLeft, targetX, targetY, targetZ);
                if (foundFrame == null) {
                    return null;
                }
                grid.add(foundFrame);
            }
        }

        return new GridData(grid, columns, rows);
    }

    @Nullable
    public static List<ItemFrame> findGrid(@NotNull ItemFrame topLeft, int columns, int rows) {
        GridVectors v = getVectors(topLeft.getFacing());
        if (v.colDx() == 0 && v.colDy() == 0 && v.colDz() == 0) return null;

        List<ItemFrame> grid = new ArrayList<>(columns * rows);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                int targetX = topLeft.getLocation().getBlockX() + (c * v.colDx()) + (r * v.rowDx());
                int targetY = topLeft.getLocation().getBlockY() + (c * v.colDy()) + (r * v.rowDy());
                int targetZ = topLeft.getLocation().getBlockZ() + (c * v.colDz()) + (r * v.rowDz());

                ItemFrame foundFrame = getFrameAt(topLeft, targetX, targetY, targetZ);
                if (foundFrame == null) return null;
                grid.add(foundFrame);
            }
        }
        return grid;
    }

    private static ItemFrame getFrameAt(ItemFrame reference, int x, int y, int z) {
        return reference.getWorld().getNearbyEntities(
                new org.bukkit.Location(reference.getWorld(), x + 0.5, y + 0.5, z + 0.5),
                0.5, 0.5, 0.5,
                entity -> entity instanceof ItemFrame && ((ItemFrame) entity).getFacing() == reference.getFacing()
        ).stream().map(e -> (ItemFrame) e).findFirst().orElse(null);
    }
}