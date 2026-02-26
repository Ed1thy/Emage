package net.edithymaster.emage.Util;

import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;

import java.util.*;

public class FrameGrid {
    public final List<FrameNode> nodes = new ArrayList<>();
    public final int width;
    public final int height;

    public static class FrameNode {
        public final ItemFrame frame;
        public final int gridX;
        public final int gridY;

        public FrameNode(ItemFrame frame, int gridX, int gridY) {
            this.frame = frame;
            this.gridX = gridX;
            this.gridY = gridY;
        }
    }

    public FrameGrid(ItemFrame startFrame, Integer reqWidth, Integer reqHeight, int maxConfigGridSize) {
        BlockFace facing = startFrame.getFacing();

        int maxConfigCells = maxConfigGridSize * maxConfigGridSize;
        int MAX_BFS = Math.max(225, maxConfigCells);
        int searchRadius = (int) Math.ceil(Math.sqrt(MAX_BFS)) + 2;

        List<ItemFrame> allNearbyFrames = new ArrayList<>();
        for (Entity e : startFrame.getWorld().getNearbyEntities(
                startFrame.getLocation(), searchRadius, searchRadius, searchRadius,
                entity -> entity instanceof ItemFrame)) {
            allNearbyFrames.add((ItemFrame) e);
        }

        Map<Long, ItemFrame> framesByPos = new HashMap<>();
        for (ItemFrame f : allNearbyFrames) {
            if (f.getFacing() == facing) {
                framesByPos.put(posKey(f), f);
            }
        }

        Set<UUID> visited = new HashSet<>();
        Queue<ItemFrame> queue = new LinkedList<>();
        Set<ItemFrame> connected = new LinkedHashSet<>();

        queue.add(startFrame);
        visited.add(startFrame.getUniqueId());
        connected.add(startFrame);

        while (!queue.isEmpty() && connected.size() < MAX_BFS) {
            ItemFrame current = queue.poll();

            for (BlockFace dir : getSearchDirections(facing)) {
                int nx = current.getLocation().getBlockX() + dir.getModX();
                int ny = current.getLocation().getBlockY() + dir.getModY();
                int nz = current.getLocation().getBlockZ() + dir.getModZ();

                ItemFrame neighbor = framesByPos.get(packPos(nx, ny, nz));
                if (neighbor != null && !visited.contains(neighbor.getUniqueId())) {
                    visited.add(neighbor.getUniqueId());
                    connected.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        for (ItemFrame f : connected) {
            int x = f.getLocation().getBlockX();
            int y = f.getLocation().getBlockY();
            int z = f.getLocation().getBlockZ();

            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }

        List<FrameNode> allNodes = new ArrayList<>();

        for (ItemFrame f : connected) {
            int wx = f.getLocation().getBlockX();
            int wy = f.getLocation().getBlockY();
            int wz = f.getLocation().getBlockZ();

            int gx, gy;

            switch (facing) {
                case NORTH -> { gx = maxX - wx; gy = maxY - wy; }
                case SOUTH -> { gx = wx - minX; gy = maxY - wy; }
                case WEST -> { gx = wz - minZ; gy = maxY - wy; }
                case EAST -> { gx = maxZ - wz; gy = maxY - wy; }
                case UP -> { gx = wx - minX; gy = maxZ - wz; }
                case DOWN -> { gx = wx - minX; gy = wz - minZ; }
                default -> { gx = 0; gy = 0; }
            }

            allNodes.add(new FrameNode(f, gx, gy));
        }

        if (reqWidth != null && reqHeight != null) {
            FrameNode anchor = null;
            for (FrameNode node : allNodes) {
                if (node.frame.getUniqueId().equals(startFrame.getUniqueId())) {
                    anchor = node;
                    break;
                }
            }

            if (anchor != null) {
                int ax = anchor.gridX;
                int ay = anchor.gridY;

                for (FrameNode node : allNodes) {
                    int rx = node.gridX - ax;
                    int ry = node.gridY - ay;

                    if (rx >= 0 && rx < reqWidth && ry >= 0 && ry < reqHeight) {
                        nodes.add(new FrameNode(node.frame, rx, ry));
                    }
                }

                this.width = reqWidth;
                this.height = reqHeight;
            } else {
                nodes.addAll(allNodes);
                this.width = calcWidth(facing, minX, maxX, minZ, maxZ);
                this.height = calcHeight(facing, minY, maxY, minZ, maxZ);
            }
        } else {
            nodes.addAll(allNodes);
            this.width = calcWidth(facing, minX, maxX, minZ, maxZ);
            this.height = calcHeight(facing, minY, maxY, minZ, maxZ);
        }
    }

    private static long posKey(ItemFrame f) {
        return packPos(f.getLocation().getBlockX(), f.getLocation().getBlockY(), f.getLocation().getBlockZ());
    }

    private static long packPos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | (((long) (y + 2048) & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    private static BlockFace[] getSearchDirections(BlockFace facing) {
        return switch (facing) {
            case UP, DOWN -> new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
            case NORTH, SOUTH -> new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.EAST, BlockFace.WEST};
            case EAST, WEST -> new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH};
            default -> new BlockFace[0];
        };
    }

    private static int calcWidth(BlockFace f, int minX, int maxX, int minZ, int maxZ) {
        return switch (f) {
            case NORTH, SOUTH, UP, DOWN -> maxX - minX + 1;
            case EAST, WEST -> maxZ - minZ + 1;
            default -> 1;
        };
    }

    private static int calcHeight(BlockFace f, int minY, int maxY, int minZ, int maxZ) {
        return switch (f) {
            case NORTH, SOUTH, EAST, WEST -> maxY - minY + 1;
            case UP, DOWN -> maxZ - minZ + 1;
            default -> 1;
        };
    }
}