package id.hadziq.arenasystem;

import java.util.Arrays;
import java.util.List;

/** Standalone regression checks for vanilla-terrain preservation and spawn fairness. */
public final class TestNaturalTerrainBalancing {
    public static void main(String[] args) {
        testPadsAreBalancedAndFlat();
        testNaturalReliefOutsideSpawnRampsIsPreserved();
        testUnsafeRouteIsRejected();
        System.out.println("PASS: natural terrain preservation, shared spawn elevation, and route fairness.");
    }

    private static TerrainProfile natural(int radius) {
        int size = radius * 2 + 1;
        int[][] heights = new int[size][size];
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int y = (int) Math.round(72
                        + 7.0 * Math.sin(x / 10.0) * Math.cos(z / 12.0)
                        + 2.0 * Math.sin((x + z) / 7.0));
                heights[x + radius][z + radius] = y;
                min = Math.min(min, y);
                max = Math.max(max, y);
            }
        }
        return new TerrainProfile(radius, heights, min, max);
    }

    private static void testPadsAreBalancedAndFlat() {
        int radius = 40;
        int offset = 27;
        List<int[]> anchors = SeededTerrainBlender.anchors(offset, 4);
        TerrainProfile base = natural(radius);
        TerrainNormalizer.Result result = TerrainNormalizer.normalizeNatural(base, anchors);
        TerrainProfile balanced = result.profile();

        int sharedY = balanced.heightAt(-offset, 0);
        for (int[] anchor : anchors) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    check(balanced.heightAt(anchor[0] + dx, anchor[1] + dz) == sharedY,
                            "Every active 9x9 spawn pad must be flat at the same Y.");
                }
            }
        }

        TerrainDetector.Report report = TerrainDetector.analyzeNatural(balanced, radius, offset, 4);
        check(report.accepted(), "Balanced natural profile should pass: " + report.rejectionReason());
        check(result.maxAdjacentStep() > 1,
                "Natural terrain relief should not be globally flattened to one-block steps.");
    }

    private static void testNaturalReliefOutsideSpawnRampsIsPreserved() {
        int radius = 40;
        int offset = 27;
        TerrainProfile base = natural(radius);
        TerrainProfile balanced = TerrainNormalizer.normalizeNatural(
                base, SeededTerrainBlender.anchors(offset, 4)).profile();

        for (int x = -14; x <= 14; x++) {
            for (int z = -14; z <= 14; z++) {
                check(balanced.heightAt(x, z) == base.heightAt(x, z),
                        "Terrain near the objective must remain unchanged by spawn balancing.");
            }
        }
        check(!Arrays.deepEquals(base.copyHeights(), balanced.copyHeights()),
                "Spawn balancing must make actual changes on the spawn pads.");
    }

    private static void testUnsafeRouteIsRejected() {
        int radius = 40;
        int offset = 27;
        int size = radius * 2 + 1;
        int[][] heights = new int[size][size];
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                // A sharp artificial ridge directly across the RED-to-objective lane.
                heights[x + radius][z + radius] = x >= -12 && x <= -10 ? 96 : 64;
            }
        }
        TerrainProfile unsafe = new TerrainProfile(radius, heights, 64, 96);
        TerrainProfile balancedPads = TerrainNormalizer.normalizeNatural(
                unsafe, SeededTerrainBlender.anchors(offset, 2)).profile();
        TerrainDetector.Report report = TerrainDetector.analyzeNatural(balancedPads, radius, offset, 2);
        check(!report.accepted(), "A route with a >3-block adjacent elevation jump must be rejected.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
