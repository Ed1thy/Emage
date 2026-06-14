package net.ed1thy.emage.util;

import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemFrame;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class GridUtil {

    public record GridData(List<ItemFrame> frames, int columns, int rows) {}

    private record GridVectors(int colDx, int colDy, int colDz, int rowDx, int rowDy, int rowDz) {
        public int getColOffset(int dx, int dy, int dz) {
            return dx * colDx + dy * colDy + dz * colDz;
        }
        public int getRowOffset(int dx, int dy, int dz) {
            return dx * rowDx + dy * rowDy + dz * rowDz;
        }
    }

    public static class MissingFrameException extends Exception {
        public final int x, y, z;
        public MissingFrameException(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

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

    private static String getLocKey(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    @Nullable
    public static GridData detectGrid(@NotNull ItemFrame clickedFrame, int inputCols, int inputRows, int maxLimit) throws MissingFrameException {
        GridVectors v = getVectors(clickedFrame.getFacing());
        if (v.colDx() == 0 && v.colDy() == 0 && v.colDz() == 0) return null;

        int searchRadius = maxLimit + 2;
        Map<String, ItemFrame> cache = new HashMap<>();
        for (org.bukkit.entity.Entity e : clickedFrame.getWorld().getNearbyEntities(clickedFrame.getLocation(), searchRadius, searchRadius, searchRadius)) {
            if (e instanceof ItemFrame f && f.getFacing() == clickedFrame.getFacing()) {
                cache.put(getLocKey(f.getLocation().getBlockX(), f.getLocation().getBlockY(), f.getLocation().getBlockZ()), f);
            }
        }

        Set<ItemFrame> visited = new HashSet<>();
        Queue<ItemFrame> queue = new LinkedList<>();
        queue.add(clickedFrame);
        visited.add(clickedFrame);

        int minCol = 0, maxCol = 0;
        int minRow = 0, maxRow = 0;

        int startX = clickedFrame.getLocation().getBlockX();
        int startY = clickedFrame.getLocation().getBlockY();
        int startZ = clickedFrame.getLocation().getBlockZ();

        while (!queue.isEmpty()) {
            ItemFrame curr = queue.poll();
            int dx = curr.getLocation().getBlockX() - startX;
            int dy = curr.getLocation().getBlockY() - startY;
            int dz = curr.getLocation().getBlockZ() - startZ;

            int col = v.getColOffset(dx, dy, dz);
            int row = v.getRowOffset(dx, dy, dz);

            minCol = Math.min(minCol, col);
            maxCol = Math.max(maxCol, col);
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);

            for (int dc = -1; dc <= 1; dc++) {
                for (int dr = -1; dr <= 1; dr++) {
                    if (dc == 0 && dr == 0) continue;
                    int nx = curr.getLocation().getBlockX() + dc * v.colDx() + dr * v.rowDx();
                    int ny = curr.getLocation().getBlockY() + dc * v.colDy() + dr * v.rowDy();
                    int nz = curr.getLocation().getBlockZ() + dc * v.colDz() + dr * v.rowDz();

                    ItemFrame neighbor = cache.get(getLocKey(nx, ny, nz));
                    if (neighbor != null && !visited.contains(neighbor)) {
                        visited.add(neighbor);
                        queue.add(neighbor);
                    }
                }
            }
        }

        int columns = inputCols == -1 ? (maxCol - minCol + 1) : inputCols;
        int rows = inputRows == -1 ? (maxRow - minRow + 1) : inputRows;

        if (columns > maxLimit || rows > maxLimit) {
            return null;
        }

        int topLeftX = startX + minCol * v.colDx() + minRow * v.rowDx();
        int topLeftY = startY + minCol * v.colDy() + minRow * v.rowDy();
        int topLeftZ = startZ + minCol * v.colDz() + minRow * v.rowDz();

        List<ItemFrame> grid = new ArrayList<>(columns * rows);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                int tx = topLeftX + c * v.colDx() + r * v.rowDx();
                int ty = topLeftY + c * v.colDy() + r * v.rowDy();
                int tz = topLeftZ + c * v.colDz() + r * v.rowDz();

                ItemFrame f = cache.get(getLocKey(tx, ty, tz));
                if (f == null) {
                    throw new MissingFrameException(tx, ty, tz);
                }
                grid.add(f);
            }
        }

        return new GridData(grid, columns, rows);
    }
}