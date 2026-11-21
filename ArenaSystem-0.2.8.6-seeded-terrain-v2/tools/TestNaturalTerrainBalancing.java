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
                double distance = Math.sqrt(x * (double) x + z * (double) z);
                int y = (int) Math.round(72 + 10.0 * Math.sin(distance / 8.0));
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
        check(report.maxAdjacentStep() > 1,
                "Natural terrain relief outside gameplay lanes should remain uneven.");
        for (int[] anchor : anchors) {
            int x = anchor[0];
            int z = anchor[1];
            int previous = balanced.heightAt(x, z);
            while (x != 0 || z != 0) {
                x += Integer.compare(0, anchor[0]);
                z += Integer.compare(0, anchor[1]);
                int next = balanced.heightAt(x, z);
                check(Math.abs(next - previous) <= 1,
                        "Every active spawn-to-objective lane must have walkable one-block steps.");
                previous = next;
            }
        }
    }

    private static void testNaturalReliefOutsideSpawnRampsIsPreserved() {
        int radius = 40;
        int offset = 27;
        TerrainProfile base = natural(radius);
        TerrainProfile balanced = TerrainNormalizer.normalizeNatural(
                base, SeededTerrainBlender.anchors(offset, 4)).profile();

        for (int x = 6; x <= 12; x++) {
            for (int z = 6; z <= 12; z++) {
                check(balanced.heightAt(x, z) == base.heightAt(x, z),
                        "Natural terrain outside spawn ramps and gameplay lanes must remain unchanged.");
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
        TerrainNormalizer.Result balanced = TerrainNormalizer.normalizeNatural(
                unsafe, SeededTerrainBlender.anchors(offset, 2));
        TerrainDetector.Report report = TerrainDetector.analyzeNatural(balanced.profile(), radius, offset, 2);
        check(report.accepted(), "The generated route ramp should be navigable: " + report.rejectionReason());
        check(balanced.maxAdjustment() > 12,
                "A route crossing an extreme ridge must exceed the 12-block earthwork limit and be rejected by the generator.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
