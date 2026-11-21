package id.hadziq.arenasystem;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Owns the lifecycle of isolated vanilla worlds used by ArenaSystem arenas.
 *
 * A marker file prevents ArenaSystem from loading or deleting an unrelated
 * server world merely because its folder happens to have a matching name.
 * All world lifecycle operations must run on the primary thread, outside the
 * world-ticking phase, as required by current Paper guidance.
 */
final class ArenaWorldManager {
    private static final String WORLD_PREFIX = "arenasystem_arena_";
    private static final String MARKER_FILE = ".arenasystem-world";
    private static final Pattern ARENA_NAME = Pattern.compile("[a-z0-9_-]{1,20}");
    private static final String MARKER_FORMAT = "format=1";

    private ArenaWorldManager() {
    }

    static String worldNameFor(String arenaName) {
        String normalized = normalizeArenaName(arenaName);
        return WORLD_PREFIX + normalized;
    }

    static boolean isManagedWorldName(String worldName) {
        if (worldName == null || !worldName.startsWith(WORLD_PREFIX)) return false;
        String arenaName = worldName.substring(WORLD_PREFIX.length());
        return ARENA_NAME.matcher(arenaName).matches();
    }

    static boolean isManagedWorld(String arenaName, String worldName) {
        if (worldName == null || arenaName == null) return false;
        String normalized = arenaName.toLowerCase(Locale.ROOT);
        return ARENA_NAME.matcher(normalized).matches() && worldName.equals(WORLD_PREFIX + normalized);
    }

    /**
     * Creates a brand-new NORMAL world using vanilla generation and structures.
     * Existing folders are rejected: a new arena must never silently reuse a
     * previous world's terrain or a world owned by another server component.
     */
    static World createNew(JavaPlugin plugin, String arenaName, long seed) throws IOException {
        requireSafeLifecycleThread();

        String worldName = worldNameFor(arenaName);
        Path folder = worldFolder(plugin, worldName);
        if (Bukkit.getWorld(worldName) != null || Files.exists(folder)) {
            throw new IllegalStateException("World arena sudah ada: " + worldName);
        }

        World world = Bukkit.createWorld(vanillaCreator(worldName, seed));
        if (world == null) {
            throw new IllegalStateException("Paper gagal membuat world arena " + worldName + ".");
        }

        try {
            if (world.getSeed() != seed) {
                throw new IllegalStateException("Seed world tidak cocok. Expected " + seed
                        + ", received " + world.getSeed() + ".");
            }
            writeMarker(world.getWorldFolder().toPath(), arenaName, seed);
            return world;
        } catch (IOException | RuntimeException ex) {
            // This folder was absent before createNew(), so cleanup cannot touch
            // a pre-existing user world. Preserve the original error if cleanup fails.
            try {
                if (Bukkit.getWorld(worldName) != null && !Bukkit.unloadWorld(world, false)) {
                    throw new IOException("World gagal di-unload setelah pembuatan dibatalkan.");
                }
                if (Files.exists(folder)) deleteRecursively(folder);
            } catch (Exception cleanupError) {
                ex.addSuppressed(cleanupError);
            }
            throw ex;
        }
    }

    /**
     * Loads an existing ArenaSystem world after verifying its ownership marker
     * and seed. It deliberately refuses to create a missing world during startup.
     */
    static World loadExisting(JavaPlugin plugin, String arenaName, long expectedSeed) throws IOException {
        requireSafeLifecycleThread();

        String worldName = worldNameFor(arenaName);
        Path folder = worldFolder(plugin, worldName);
        verifyMarker(folder, arenaName, expectedSeed);

        World loaded = Bukkit.getWorld(worldName);
        if (loaded == null) {
            loaded = Bukkit.createWorld(vanillaCreator(worldName, expectedSeed));
        }
        if (loaded == null) {
            throw new IllegalStateException("World arena tidak dapat dimuat: " + worldName);
        }
        if (loaded.getSeed() != expectedSeed) {
            throw new IllegalStateException("Seed world arena '" + worldName + "' berubah; "
                    + "expected " + expectedSeed + ", received " + loaded.getSeed() + ".");
        }
        return loaded;
    }

    /**
     * Deletes only an ArenaSystem-owned world. The caller must first move all
     * players out of the world and durably remove the arena registration.
     */
    static boolean deleteManagedWorld(JavaPlugin plugin, String arenaName, long expectedSeed) throws IOException {
        requireSafeLifecycleThread();

        String worldName = worldNameFor(arenaName);
        Path folder = worldFolder(plugin, worldName);
        verifyMarker(folder, arenaName, expectedSeed);

        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            if (!world.getPlayers().isEmpty()) return false;
            if (!Bukkit.unloadWorld(world, true)) return false;
        }

        deleteRecursively(folder);
        return true;
    }

    /**
     * Removes marker-validated worlds whose arena registration is absent. This
     * closes the crash window between world creation and the first durable arena
     * record, and also cleans worlds left behind after a completed deletion.
     */
    static int cleanupOrphanedWorlds(JavaPlugin plugin, java.util.Set<String> registeredArenaNames)
            throws IOException {
        requireSafeLifecycleThread();

        Path container = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        int deleted = 0;
        try (Stream<Path> children = Files.list(container)) {
            for (Path folder : children.toList()) {
                String folderName = folder.getFileName().toString();
                if (!isManagedWorldName(folderName)) continue;

                Path markerPath = folder.resolve(MARKER_FILE);
                if (!Files.isDirectory(folder) || Files.isSymbolicLink(markerPath)
                        || !Files.isRegularFile(markerPath)) continue;

                String[] lines = Files.readString(markerPath, StandardCharsets.UTF_8).split("\\R");
                if (lines.length != 3 || !MARKER_FORMAT.equals(lines[0])
                        || !lines[1].startsWith("arena=") || !lines[2].startsWith("seed=")) {
                    continue;
                }

                String arenaName = lines[1].substring("arena=".length());
                if (!ARENA_NAME.matcher(arenaName).matches()
                        || !folderName.equals(worldNameFor(arenaName))
                        || registeredArenaNames.contains(arenaName)) {
                    continue;
                }

                final long seed;
                try {
                    seed = Long.parseLong(lines[2].substring("seed=".length()));
                } catch (NumberFormatException ex) {
                    continue;
                }

                if (deleteManagedWorld(plugin, arenaName, seed)) deleted++;
            }
        }
        return deleted;
    }

    /**
     * Removes a newly-created world when generation fails before ArenaData is
     * persisted. The marker supplies the seed, but its arena identity and format
     * are still validated before any directory is deleted.
     */
    static boolean deleteUnregisteredWorld(JavaPlugin plugin, String arenaName) throws IOException {
        requireSafeLifecycleThread();

        String worldName = worldNameFor(arenaName);
        Path folder = worldFolder(plugin, worldName);
        Path markerPath = folder.resolve(MARKER_FILE);
        if (Files.isSymbolicLink(markerPath) || !Files.isRegularFile(markerPath)) return false;

        String marker = Files.readString(markerPath, StandardCharsets.UTF_8);
        String expectedPrefix = MARKER_FORMAT + "\n" + "arena=" + normalizeArenaName(arenaName) + "\n";
        if (!marker.startsWith(expectedPrefix)) {
            throw new IOException("Marker world ArenaSystem tidak cocok; world tidak dihapus.");
        }

        String seedLine = marker.substring(expectedPrefix.length()).trim();
        if (!seedLine.startsWith("seed=")) {
            throw new IOException("Marker world ArenaSystem rusak; world tidak dihapus.");
        }

        final long seed;
        try {
            seed = Long.parseLong(seedLine.substring("seed=".length()));
        } catch (NumberFormatException ex) {
            throw new IOException("Seed pada marker world ArenaSystem tidak valid.", ex);
        }
        return deleteManagedWorld(plugin, arenaName, seed);
    }

    private static WorldCreator vanillaCreator(String worldName, long seed) {
        return WorldCreator.name(worldName)
                .seed(seed)
                .environment(World.Environment.NORMAL)
                .type(WorldType.NORMAL)
                .generateStructures(true);
    }

    private static String normalizeArenaName(String arenaName) {
        if (arenaName == null) throw new IllegalArgumentException("Nama arena tidak boleh null.");
        String normalized = arenaName.toLowerCase(Locale.ROOT);
        if (!ARENA_NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Nama arena tidak valid: " + arenaName);
        }
        return normalized;
    }

    private static Path worldFolder(JavaPlugin plugin, String worldName) throws IOException {
        Path container = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        Path folder = container.resolve(worldName).normalize();
        if (!folder.getParent().equals(container)) {
            throw new IOException("Path world arena berada di luar world container.");
        }
        if (Files.isSymbolicLink(folder)) {
            throw new IOException("Folder world arena tidak boleh berupa symbolic link.");
        }
        return folder;
    }

    private static void writeMarker(Path folder, String arenaName, long seed) throws IOException {
        String marker = markerContents(arenaName, seed);
        Files.writeString(folder.resolve(MARKER_FILE), marker, StandardCharsets.UTF_8);
    }

    private static void verifyMarker(Path folder, String arenaName, long seed) throws IOException {
        Path markerPath = folder.resolve(MARKER_FILE);
        if (Files.isSymbolicLink(markerPath)
                || !Files.isDirectory(folder) || !Files.isRegularFile(markerPath)) {
            throw new IOException("Folder/marker world bukan world ArenaSystem yang tervalidasi: " + folder);
        }
        String actual = Files.readString(markerPath, StandardCharsets.UTF_8);
        String expected = markerContents(arenaName, seed);
        if (!expected.equals(actual)) {
            throw new IOException("Marker world ArenaSystem tidak cocok untuk arena '"
                    + arenaName + "' (seed " + seed + "). Operasi dibatalkan demi keamanan data.");
        }
    }

    private static String markerContents(String arenaName, long seed) {
        return MARKER_FORMAT + "\n"
                + "arena=" + normalizeArenaName(arenaName) + "\n"
                + "seed=" + seed + "\n";
    }

    private static void requireSafeLifecycleThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("World lifecycle ArenaSystem harus berjalan pada primary thread.");
        }
        if (Bukkit.isTickingWorlds()) {
            throw new IllegalStateException("World lifecycle ditunda karena Paper sedang men-tick world.");
        }
    }

    private static void deleteRecursively(Path folder) throws IOException {
        if (!Files.exists(folder)) return;
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
