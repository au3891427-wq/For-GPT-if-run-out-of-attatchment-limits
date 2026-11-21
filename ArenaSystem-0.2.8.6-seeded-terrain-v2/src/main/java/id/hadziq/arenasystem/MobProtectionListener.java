package id.hadziq.arenasystem;

import org.bukkit.Location;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.NPC;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.PotionSplashEvent;

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

    /**
     * Keanggotaan match tetap menjadi acuan ketika knockback atau perpindahan paksa
     * menempatkan peserta sedikit di luar footprint. Perlindungan dibatasi ke dunia
     * arena dan lifecycle pertandingan agar antrean lama tidak mendapat imunitas.
     */
    private boolean isMatchParticipant(Player player) {
        if (player == null) return false;

        for (MatchState state : plugin.matches.values()) {
            boolean activeLifecycle = state.running || state.preparing || state.launching || state.ending;
            if (!activeLifecycle || !state.teams.containsKey(player.getUniqueId())) continue;

            ArenaData arena = plugin.arenas.get(state.arenaName);
            if (arena != null && arena.worldName.equals(player.getWorld().getName())) {
                return true;
            }
        }
        return false;
    }

    /** Pemain di footprint tetap terlindungi; peserta aktif juga terlindungi saat terdorong keluar. */
    private boolean isProtectedPlayer(Player player) {
        return player != null
                && (plugin.findArenaFootprint(player.getLocation()) != null || isMatchParticipant(player));
    }

    /**
     * Periksa entitas penyebab dan entitas yang langsung memberikan damage.
     * DamageSource.getCausingEntity() mencakup proyektil yang ditembakkan mob;
     * pemeriksaan entitas langsung menjadi fallback untuk projectile/fang/cloud.
     */
    private boolean isHostileDamageSource(Entity source) {
        if (source == null) return false;
        if (isProtectedEnemy(source)) return true;

        if (source instanceof Projectile projectile
                && projectile.getShooter() instanceof Entity shooter
                && isProtectedEnemy(shooter)) {
            return true;
        }
        if (source instanceof EvokerFangs fangs && isProtectedEnemy(fangs.getOwner())) {
            return true;
        }
        return source instanceof AreaEffectCloud cloud
                && cloud.getSource() instanceof Entity shooter
                && isProtectedEnemy(shooter);
    }

    private boolean isHostilePotionSource(org.bukkit.projectiles.ProjectileSource source) {
        return source instanceof Entity entity && isProtectedEnemy(entity);
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

        // Hostile mob di luar arena tidak boleh mengunci pemain yang berada di dalam
        // footprint ATAU masih terdaftar sebagai peserta pertandingan aktif.
        LivingEntity target = event.getTarget();
        if ((target instanceof Player player && isProtectedPlayer(player))
                || (target != null && plugin.findArenaFootprint(target.getLocation()) != null)) {
            event.setCancelled(true);
        }
    }

    /**
     * Lapisan terakhir untuk damage hostile. Ini juga menangkap Sonic Boom Warden
     * yang tidak harus melewati proses pemilihan target biasa.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHostileDamageToMatchParticipant(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !isProtectedPlayer(player)) return;

        if (event.getCause() == EntityDamageEvent.DamageCause.SONIC_BOOM) {
            event.setCancelled(true);
            return;
        }

        var damageSource = event.getDamageSource();
        if (isHostileDamageSource(damageSource.getCausingEntity())
                || isHostileDamageSource(damageSource.getDirectEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * Cegah splash potion dari mob hostile memberikan efek kepada pemain yang dilindungi,
     * tanpa mengganggu efek potion terhadap entitas lain di sekitarnya.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHostilePotionSplash(PotionSplashEvent event) {
        if (!isHostilePotionSource(event.getPotion().getShooter())) return;

        for (LivingEntity affected : event.getAffectedEntities()) {
            if (affected instanceof Player player && isProtectedPlayer(player)) {
                event.setIntensity(affected, 0.0D);
            }
        }
    }

    /**
     * Daftar target lingering cloud dapat diubah. Hapus hanya pemain yang dilindungi
     * dari daftar penerima efek; entitas lain di sekitarnya tetap diproses.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHostileAreaEffectCloud(AreaEffectCloudApplyEvent event) {
        if (!isHostilePotionSource(event.getEntity().getSource())) return;

        event.getAffectedEntities().removeIf(entity ->
                entity instanceof Player player && isProtectedPlayer(player));
    }

    /** Cegah sumber api dari mob hostile membakar peserta pertandingan. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHostileCombustParticipant(EntityCombustByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player) || !isProtectedPlayer(player)) return;
        if (isHostileDamageSource(event.getCombuster())) {
            event.setCancelled(true);
        }
    }

    private boolean isBlastMineMarker(Entity entity) {
        return entity != null
                && entity.getScoreboardTags().contains(ClassAbilityListener.BLAST_MINE_MARKER_TAG);
    }
}
