package id.hadziq.arenasystem;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.util.BoundingBox;

final class TerrainScanner {
    private TerrainScanner() {
    }

    static TerrainProfile scan(World world, int centerX, int centerZ, int radius) {
        int size = radius * 2 + 1;
        int[][] heights = new int[size][size];
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int surfaceY = findSurface(world, centerX + x, centerZ + z);
                if (surfaceY == Integer.MIN_VALUE) return null;
                heights[x + radius][z + radius] = surfaceY;
                minY = Math.min(minY, surfaceY);
                maxY = Math.max(maxY, surfaceY);
            }
        }
        return new TerrainProfile(radius, heights, minY, maxY);
    }

    /**
     * Returns a description when a vanilla-generated structure intersects the
     * arena footprint. The caller must scan/generate the footprint first so the
     * queried chunks have had their structure starts/placements materialized.
     * Structures are rejected rather than overwritten by arena terrain edits.
     */
    static String findIntersectingGeneratedStructure(World world, int centerX, int centerZ, int radius,
                                                     int minSurfaceY, int maxSurfaceY) {
        // Ignore deep underground structures that the arena never touches, but
        // include likely foundations below grade and tree/building clearance above it.
        double minY = Math.max(world.getMinHeight(), minSurfaceY - 12.0D);
        double maxY = Math.min(world.getMaxHeight(), maxSurfaceY + 32.0D);
        BoundingBox arenaBounds = new BoundingBox(
                centerX - radius, minY, centerZ - radius,
                centerX + radius + 1.0D, maxY, centerZ + radius + 1.0D
        );

        int minChunkX = Math.floorDiv(centerX - radius, 16);
        int maxChunkX = Math.floorDiv(centerX + radius, 16);
        int minChunkZ = Math.floorDiv(centerZ - radius, 16);
        int maxChunkZ = Math.floorDiv(centerZ + radius, 16);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                for (var structure : world.getStructures(chunkX, chunkZ)) {
                    BoundingBox structureBounds = structure.getBoundingBox();
                    if (structureBounds.overlaps(arenaBounds)) {
                        return structure.getStructure().getStructureType()
                                + " @ " + structureBounds;
                    }
                }
            }
        }
        return null;
    }

    static int findSurface(World world, int x, int z) {
        Block highest = world.getHighestBlockAt(x, z);
        // A water/lava top can be used as a build surface; the platform replaces only its top layer.
        if (highest.isLiquid()) return highest.getY();
        for (int y = highest.getY(); y >= world.getMinHeight(); y--) {
            Block block = world.getBlockAt(x, y, z);
            Material material = block.getType();
            if (!material.isSolid() || material.isAir() || isFoliage(material)) continue;
            if (block.getState() instanceof TileState) continue;
            return y;
        }
        return Integer.MIN_VALUE;
    }

    private static boolean isFoliage(Material material) {
        String name = material.name();
        return name.endsWith("_LEAVES") || name.endsWith("_LOG") || name.endsWith("_WOOD")
                || name.equals("AZALEA") || name.equals("FLOWERING_AZALEA");
    }
}
