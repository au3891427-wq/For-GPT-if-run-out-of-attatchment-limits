package id.hadziq.arenasystem;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;

final class CompanionClientListener implements Listener, PluginMessageListener {
   static final String CHANNEL = "arenasystem:mod_handshake";
   private static final String BYPASS_PERMISSION = "arenasystem.client.bypass";
   private static final long CHALLENGE_DELAY_TICKS = 2L;
   private final JavaPlugin plugin;
   private final Map<UUID, CompanionClientListener.PendingHandshake> pending = new HashMap<>();
   private final Map<UUID, BukkitTask> scheduledChallenges = new HashMap<>();
   private final Set<UUID> gatedPlayers = new HashSet<>();
   private boolean closed;

   CompanionClientListener(JavaPlugin var1) {
      this.plugin = var1;
      Messenger var2 = var1.getServer().getMessenger();
      var2.registerIncomingPluginChannel(var1, "arenasystem:mod_handshake", this);
      var2.registerOutgoingPluginChannel(var1, "arenasystem:mod_handshake");
   }

   void start() {
      for (Player var3 : Bukkit.getOnlinePlayers()) {
         this.scheduleChallenge(var3);
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST)
   public void onPlayerJoin(PlayerJoinEvent var1) {
      this.scheduleChallenge(var1.getPlayer());
   }

   @EventHandler
   public void onPlayerQuit(PlayerQuitEvent var1) {
      UUID var2 = var1.getPlayer().getUniqueId();
      this.cancelScheduledChallenge(var2);
      this.cancelPendingHandshake(var2);
      this.gatedPlayers.remove(var2);
   }

   private void scheduleChallenge(Player var1) {
      if (!this.closed && !this.isBypassed(var1)) {
         UUID var2 = var1.getUniqueId();
         this.cancelScheduledChallenge(var2);
         this.cancelPendingHandshake(var2);
         this.gatedPlayers.add(var2);
         var1.setGameMode(GameMode.SPECTATOR);
         BukkitTask var3 = Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            this.scheduledChallenges.remove(var2);
            if (!this.closed && var1.isOnline() && !this.isBypassed(var1)) {
               this.sendChallenge(var1);
            }
         }, 2L);
         this.scheduledChallenges.put(var2, var3);
      }
   }

   private boolean isBypassed(Player var1) {
      return var1.isOp() || var1.hasPermission("arenasystem.client.bypass");
   }

   private void sendChallenge(Player var1) {
      UUID var2 = var1.getUniqueId();
      UUID var3 = UUID.randomUUID();
      long var4 = Math.max(5L, Math.min(60L, this.plugin.getConfig().getLong("client-mod.timeout-seconds", 10L)));
      BukkitTask var6 = Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.handleTimeout(var2, var3), var4 * 20L);
      this.pending.put(var2, new CompanionClientListener.PendingHandshake(var3, var6));

      try {
         var1.sendPluginMessage(this.plugin, "arenasystem:mod_handshake", CompanionHandshakeProtocol.encodeChallenge(var3));
      } catch (RuntimeException var8) {
         this.plugin.getLogger().warning("Gagal mengirim challenge client mod ke " + var1.getName() + ": " + var8.getMessage());
      }
   }

   public void onPluginMessageReceived(String var1, Player var2, byte[] var3) {
      if (!this.closed && "arenasystem:mod_handshake".equals(var1) && var2 != null) {
         UUID var4 = var2.getUniqueId();
         CompanionClientListener.PendingHandshake var5 = this.pending.get(var4);
         if (var5 != null) {
            if (!CompanionHandshakeProtocol.isValidResponse(var3, var5.nonce())) {
               this.plugin.getLogger().fine("Mengabaikan respons handshake client mod yang tidak valid dari " + var2.getName() + ".");
            } else {
               this.pending.remove(var4);
               var5.timeoutTask().cancel();
               this.gatedPlayers.remove(var4);
               GameMode var6 = this.plugin.getServer().getDefaultGameMode();
               var2.setGameMode(var6);
               this.plugin
                  .getLogger()
                  .info("[ClientMod] " + var2.getName() + " berhasil diverifikasi; gamemode dikembalikan ke default server (" + var6 + ").");
            }
         }
      }
   }

   private void handleTimeout(UUID var1, UUID var2) {
      CompanionClientListener.PendingHandshake var3 = this.pending.get(var1);
      if (var3 != null && var3.nonce().equals(var2)) {
         this.pending.remove(var1);
         this.gatedPlayers.remove(var1);
         Player var4 = Bukkit.getPlayer(var1);
         if (var4 != null && var4.isOnline()) {
            this.plugin.getLogger().warning("[ClientMod] " + var4.getName() + " ditolak karena companion client mod tidak menyelesaikan verifikasi.");
            var4.kickPlayer(
               ChatColor.RED
                  + "[ArenaSystem] Verifikasi client mod gagal.\n"
                  + ChatColor.YELLOW
                  + "Pasang Fabric API dan ArenaSystem Client Mod yang sesuai, lalu masuk kembali."
            );
         }
      }
   }

   private void cancelScheduledChallenge(UUID var1) {
      BukkitTask var2 = this.scheduledChallenges.remove(var1);
      if (var2 != null) {
         var2.cancel();
      }
   }

   private void cancelPendingHandshake(UUID var1) {
      CompanionClientListener.PendingHandshake var2 = this.pending.remove(var1);
      if (var2 != null) {
         var2.timeoutTask().cancel();
      }
   }

   private boolean isGated(Player var1) {
      return this.gatedPlayers.contains(var1.getUniqueId());
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
   public void onPendingMove(PlayerMoveEvent var1) {
      if (this.isGated(var1.getPlayer()) && var1.getTo() != null) {
         Location var2 = var1.getFrom();
         Location var3 = var1.getTo();
         boolean var4 = var2.getWorld() != var3.getWorld() || var2.getX() != var3.getX() || var2.getY() != var3.getY() || var2.getZ() != var3.getZ();
         if (var4) {
            var1.setTo(new Location(var2.getWorld(), var2.getX(), var2.getY(), var2.getZ(), var3.getYaw(), var3.getPitch()));
         }
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
   public void onPendingCommand(PlayerCommandPreprocessEvent var1) {
      if (this.isGated(var1.getPlayer())) {
         var1.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
   public void onPendingInteract(PlayerInteractEvent var1) {
      if (this.isGated(var1.getPlayer())) {
         var1.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
   public void onPendingEntityInteract(PlayerInteractEntityEvent var1) {
      if (this.isGated(var1.getPlayer())) {
         var1.setCancelled(true);
      }
   }

   void shutdown() {
      if (!this.closed) {
         this.closed = true;

         for (BukkitTask var2 : this.scheduledChallenges.values()) {
            var2.cancel();
         }

         this.scheduledChallenges.clear();

         for (CompanionClientListener.PendingHandshake var7 : this.pending.values()) {
            var7.timeoutTask().cancel();
         }

         this.pending.clear();
         GameMode var6 = this.plugin.getServer().getDefaultGameMode();

         for (UUID var3 : new HashSet<>(this.gatedPlayers)) {
            Player var4 = Bukkit.getPlayer(var3);
            if (var4 != null && var4.isOnline()) {
               var4.setGameMode(var6);
            }
         }

         this.gatedPlayers.clear();
         Messenger var9 = this.plugin.getServer().getMessenger();
         var9.unregisterIncomingPluginChannel(this.plugin, "arenasystem:mod_handshake", this);
         var9.unregisterOutgoingPluginChannel(this.plugin, "arenasystem:mod_handshake");
      }
   }

   private record PendingHandshake(UUID nonce, BukkitTask timeoutTask) {
   }
}
