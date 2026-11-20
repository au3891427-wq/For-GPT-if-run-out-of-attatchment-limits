package id.hadziq.arenasystem;

import org.bukkit.Location;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.NPC;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityTeleportEvent;

import io.papermc.paper.event.entity.EntityMoveEvent;

/**
 * Menjaga area arena steril dari entity hostile.
 *
 * Passive mob, hewan tame, marker Blast Mine, dan NPC tidak dihapus/diblokir.
 */
final class MobProtectionListener implements Listener {
    private final ArenaSystemPlugin plugin;

    MobProtectionListener(ArenaSystemPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isProtectedEnemy(Entity entity) {
        if (!(entity instanceof Enemy)) return false;
        if (entity instanceof Player || entity instanceof NPC || isBlastMineMarker(entity)) return false;
        if (entity.hasMetadata("NPC")) return false; // kompatibilitas NPC plugin lama seperti Citizens
        if (entity instanceof Tameable tameable && tameable.isTamed()) return false;
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        Entity entity = event.getEntity();
        // Marker Blast Mine harus tetap dapat dibuat di dalam arena, sama seperti
        // perilaku listener sebelum filter hostile diperketat.
        if (isBlastMineMarker(entity)) {
            event.setCancelled(false);
            return;
        }
        if (!isProtectedEnemy(entity)) return;

        if (plugin.findArenaFootprint(event.getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    /** Mencegah hostile mob berjalan dari luar menuju arena. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMobEnterArena(EntityMoveEvent event) {
        Entity entity = event.getEntity();
        if (!isProtectedEnemy(entity)) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        ArenaData destinationArena = plugin.findArenaFootprint(to);
        if (destinationArena == null) return;

        if (plugin.findArenaFootprint(from) != destinationArena) {
            event.setCancelled(true);
        }
    }

    /** Mencegah hostile mob masuk lewat teleport/portal/command. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMobTeleportIntoArena(EntityTeleportEvent event) {
        Entity entity = event.getEntity();
        if (!isProtectedEnemy(entity)) return;

        Location to = event.getTo();
        if (to == null) return;

        ArenaData destinationArena = plugin.findArenaFootprint(to);
        if (destinationArena == null) return;

        if (plugin.findArenaFootprint(event.getFrom()) != destinationArena) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onArenaChunkLoad(org.bukkit.event.world.ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (!isProtectedEnemy(entity)) continue;
            if (plugin.findArenaFootprint(entity.getLocation()) != null) {
                entity.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityTarget(org.bukkit.event.entity.EntityTargetLivingEntityEvent event) {
        Entity attacker = event.getEntity();
        if (!isProtectedEnemy(attacker)) return;

        if (plugin.findArenaFootprint(attacker.getLocation()) != null) {
            attacker.remove();
            event.setCancelled(true);
            return;
        }

        // Hostile mob di luar arena tidak boleh mengunci target pemain di dalam arena.
        org.bukkit.entity.LivingEntity target = event.getTarget();
        if (target != null && plugin.findArenaFootprint(target.getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    private boolean isBlastMineMarker(Entity entity) {
        return entity != null
                && entity.getScoreboardTags().contains(ClassAbilityListener.BLAST_MINE_MARKER_TAG);
    }
}
