package id.hadziq.arenasystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Evaluates an already-scanned height map for arena suitability. It does not
 * modify the world. The final surface must never exceed one block of vertical
 * difference between horizontally adjacent columns.
 */
final class TerrainDetector {
    private TerrainDetector() { }

    static Report analyze(TerrainProfile terrain, int radius, int spawnOffset) {
        return analyze(terrain, radius, spawnOffset, 2);
    }

    /**
     * Dedicated-world validation. Natural terrain may contain cliffs and uneven
     * relief outside combat lanes, so only the actual spawn-to-objective corridors
     * are constrained. Route effort is compared across active teams as a first-pass
     * fairness check; this is not a substitute for later obstacle/pathfinding analysis.
     */
    static Report analyzeNatural(TerrainProfile terrain, int radius, int spawnOffset, int teamCount) {
        int range = terrain.maxY - terrain.minY;
        long edgeCount = 0L;
        long totalStep = 0L;
        int maxAdjacentStep = 0;

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int here = terrain.heightAt(x, z);
                if (x < radius) {
                    int step = Math.abs(here - terrain.heightAt(x + 1, z));
                    maxAdjacentStep = Math.max(maxAdjacentStep, step);
                    totalStep += step;
                    edgeCount++;
                }
                if (z < radius) {
                    int step = Math.abs(here - terrain.heightAt(x, z + 1));
                    maxAdjacentStep = Math.max(maxAdjacentStep, step);
                    totalStep += step;
                    edgeCount++;
                }
            }
        }

        int spawnLow = Integer.MAX_VALUE;
        int spawnHigh = Integer.MIN_VALUE;
        long routeTotalStep = 0L;
        int routeEdges = 0;
        int routeMaxStep = 0;
        double minRouteCost = Double.POSITIVE_INFINITY;
        double maxRouteCost = 0.0D;

        for (int[] anchor : SeededTerrainBlender.anchors(spawnOffset, teamCount)) {
            int startX = anchor[0];
            int startZ = anchor[1];
            int spawnY = terrain.heightAt(startX, startZ);
            spawnLow = Math.min(spawnLow, spawnY);
            spawnHigh = Math.max(spawnHigh, spawnY);

            int stepX = Integer.compare(0, startX);
            int stepZ = Integer.compare(0, startZ);
            int currentX = startX;
            int currentZ = startZ;
            int previousY = spawnY;
            int totalAscent = 0;
            int totalDescent = 0;

            while (currentX != 0 || currentZ != 0) {
                currentX += stepX;
                currentZ += stepZ;
                int nextY = terrain.heightAt(currentX, currentZ);
                int delta = nextY - previousY;
                int absoluteStep = Math.abs(delta);
                routeMaxStep = Math.max(routeMaxStep, absoluteStep);
                routeTotalStep += absoluteStep;
                routeEdges++;
                if (delta > 0) totalAscent += delta;
                else totalDescent -= delta;
                previousY = nextY;
            }

            // Climbing is deliberately weighted more than descending. All routes
            // have equal horizontal length, so terrain is the source of the delta.
            double routeCost = spawnOffset + 1.5D * totalAscent + 0.75D * totalDescent;
            minRouteCost = Math.min(minRouteCost, routeCost);
            maxRouteCost = Math.max(maxRouteCost, routeCost);
        }

        int spawnDifference = spawnHigh == Integer.MIN_VALUE ? 0 : spawnHigh - spawnLow;
        double averageStep = edgeCount == 0L ? 0.0D : (double) totalStep / edgeCount;
        double routeAverage = routeEdges == 0 ? 0.0D : (double) routeTotalStep / routeEdges;
        double routeSpread = minRouteCost <= 0.0D || !Double.isFinite(minRouteCost)
                ? 1.0D : maxRouteCost / minRouteCost;

        List<String> warnings = new ArrayList<>();
        List<String> rejection = new ArrayList<>();
        if (range > 48) rejection.add("rentang elevasi " + range + " blok (maksimum 48)");
        else if (range > 28) warnings.add("rentang elevasi cukup besar: " + range + " blok");

        if (spawnDifference != 0) {
            rejection.add("elevasi spawn aktif belum sama (" + spawnDifference + " blok)");
        }
        if (routeMaxStep > 3) {
            rejection.add("jalur spawn ke objective memiliki lonjakan " + routeMaxStep
                    + " blok per langkah (maksimum 3)");
        }
        if (routeSpread > 1.45D) {
            rejection.add("perkiraan beban perjalanan antartim terlalu timpang (rasio "
                    + String.format(Locale.ROOT, "%.2f", routeSpread) + ", maksimum 1.45)");
        } else if (routeSpread > 1.25D) {
            warnings.add("perkiraan beban perjalanan antartim berbeda "
                    + String.format(Locale.ROOT, "%.0f", (routeSpread - 1.0D) * 100.0D) + "%");
        }

        return new Report(range, maxAdjacentStep, averageStep, spawnDifference,
                routeMaxStep, routeAverage, warnings, rejection);
    }

    /** teamCount 3 adds GREEN, 4 adds GREEN and YELLOW (Z axis) to the spawn-fairness check; inactive teams are ignored. */
    static Report analyze(TerrainProfile terrain, int radius, int spawnOffset, int teamCount) {
        int range = terrain.maxY - terrain.minY;
        long edgeCount = 0;
        long totalStep = 0;
        int maxStep = 0;

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int here = terrain.heightAt(x, z);
                if (x < radius) {
                    int step = Math.abs(here - terrain.heightAt(x + 1, z));
                    edgeCount++;
                    totalStep += step;
                    maxStep = Math.max(maxStep, step);
                }
                if (z < radius) {
                    int step = Math.abs(here - terrain.heightAt(x, z + 1));
                    edgeCount++;
                    totalStep += step;
                    maxStep = Math.max(maxStep, step);
                }
            }
        }

        int routeMaxStep = 0;
        long routeTotalStep = 0;
        int routeEdges = 0;
        for (int x = -spawnOffset; x < spawnOffset; x++) {
            int step = Math.abs(terrain.heightAt(x, 0) - terrain.heightAt(x + 1, 0));
            routeMaxStep = Math.max(routeMaxStep, step);
            routeTotalStep += step;
            routeEdges++;
        }

        int spawnLow = Math.min(terrain.heightAt(-spawnOffset, 0), terrain.heightAt(spawnOffset, 0));
        int spawnHigh = Math.max(terrain.heightAt(-spawnOffset, 0), terrain.heightAt(spawnOffset, 0));
        for (int[] anchor : SeededTerrainBlender.anchors(spawnOffset, teamCount)) {
            int h = terrain.heightAt(anchor[0], anchor[1]);
            spawnLow = Math.min(spawnLow, h);
            spawnHigh = Math.max(spawnHigh, h);
        }
        int spawnDifference = spawnHigh - spawnLow;
        double averageStep = edgeCount == 0 ? 0.0 : (double) totalStep / edgeCount;
        double routeAverage = routeEdges == 0 ? 0.0 : (double) routeTotalStep / routeEdges;

        List<String> warnings = new ArrayList<>();
        List<String> rejection = new ArrayList<>();
        if (range > 48) rejection.add("rentang elevasi " + range + " blok (maksimum 48)");
        else if (range > 28) warnings.add("rentang elevasi cukup besar: " + range + " blok");

        if (spawnDifference > 8) {
            rejection.add("selisih elevasi spawn antar tim " + spawnDifference + " blok (maksimum 8)");
        } else if (spawnDifference > 4) {
            warnings.add("spawn antar tim berbeda tinggi " + spawnDifference + " blok");
        }

        if (maxStep > 1) {
            rejection.add("beda tinggi antarkolom mencapai " + maxStep + " blok (batas wajib 1)");
        }
        if (routeMaxStep > 1) {
            rejection.add("jalur utama RED ke BLUE memiliki beda tinggi " + routeMaxStep
                    + " blok per langkah (batas 1)");
        }

        return new Report(range, maxStep, averageStep, spawnDifference, routeMaxStep,
                routeAverage, warnings, rejection);
    }

    static final class Report {
        private final int elevationRange;
        private final int maxAdjacentStep;
        private final int spawnDifference;
        private final int routeMaxStep;
        private final double averageStep;
        private final double routeAverageStep;
        private final List<String> warnings;
        private final List<String> rejectionReasons;

        private Report(int elevationRange, int maxAdjacentStep, double averageStep,
                       int spawnDifference, int routeMaxStep, double routeAverageStep,
                       List<String> warnings, List<String> rejectionReasons) {
            this.elevationRange = elevationRange;
            this.maxAdjacentStep = maxAdjacentStep;
            this.averageStep = averageStep;
            this.spawnDifference = spawnDifference;
            this.routeMaxStep = routeMaxStep;
            this.routeAverageStep = routeAverageStep;
            this.warnings = List.copyOf(warnings);
            this.rejectionReasons = List.copyOf(rejectionReasons);
        }

        boolean accepted() { return rejectionReasons.isEmpty(); }
        List<String> warnings() { return warnings; }
        String rejectionReason() { return String.join("; ", rejectionReasons); }
        int maxAdjacentStep() { return maxAdjacentStep; }

        String summary() {
            String status = accepted() ? (warnings.isEmpty() ? "LAYAK" : "LAYAK DENGAN CATATAN") : "DITOLAK";
            return status + " | elevasi=" + elevationRange + " | lonjakan tetangga maks=" + maxAdjacentStep
                    + " | rata-rata beda tinggi=" + String.format(Locale.ROOT, "%.2f", averageStep)
                    + " | spawn diff=" + spawnDifference + " | lonjakan jalur=" + routeMaxStep
                    + " | rata-rata jalur=" + String.format(Locale.ROOT, "%.2f", routeAverageStep);
        }
    }
}
