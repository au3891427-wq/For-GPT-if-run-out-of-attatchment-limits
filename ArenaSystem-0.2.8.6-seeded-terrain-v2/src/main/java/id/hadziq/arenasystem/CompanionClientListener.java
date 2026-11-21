package id.hadziq.arenasystem;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Requires the ArenaSystem Fabric companion to answer a per-connection challenge.
 *
 * This is a compatibility/presence handshake, not cryptographic client attestation:
 * a modified client can imitate the packet. Gameplay validation remains server-side.
 */
final class CompanionClientListener implements Listener, PluginMessageListener {
    static final String CHANNEL = "arenasystem:mod_handshake";
    private static final String BYPASS_PERMISSION = "arenasystem.client.bypass";
    private static final long CHALLENGE_DELAY_TICKS = 2L;

    private record PendingHandshake(UUID nonce, BukkitTask timeoutTask) { }

    private final JavaPlugin plugin;
    private final Map<UUID, PendingHandshake> pending = new HashMap<>();
    private final Map<UUID, BukkitTask> scheduledChallenges = new HashMap<>();
    private boolean closed;

    CompanionClientListener(JavaPlugin plugin) {
        this.plugin = plugin;
        Messenger messenger = plugin.getServer().getMessenger();
        messenger.registerIncomingPluginChannel(plugin, CHANNEL, this);
        messenger.registerOutgoingPluginChannel(plugin, CHANNEL);
    }

    void start() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            scheduleChallenge(player);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        scheduleChallenge(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        cancelScheduledChallenge(playerId);
        cancelPendingHandshake(playerId);
    }

    private void scheduleChallenge(Player player) {
        if (closed || isBypassed(player)) return;

        UUID playerId = player.getUniqueId();
        cancelScheduledChallenge(playerId);
        cancelPendingHandshake(playerId);

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            scheduledChallenges.remove(playerId);
            if (!closed && player.isOnline() && !isBypassed(player)) {
                sendChallenge(player);
            }
        }, CHALLENGE_DELAY_TICKS);
        scheduledChallenges.put(playerId, task);
    }

    private boolean isBypassed(Player player) {
        return player.isOp() || player.hasPermission(BYPASS_PERMISSION);
    }

    private void sendChallenge(Player player) {
        UUID playerId = player.getUniqueId();
        UUID nonce = UUID.randomUUID();
        long timeoutSeconds = Math.max(5L,
                Math.min(60L, plugin.getConfig().getLong("client-mod.timeout-seconds", 10L)));

        BukkitTask timeoutTask = Bukkit.getScheduler().runTaskLater(plugin,
                () -> handleTimeout(playerId, nonce), timeoutSeconds * 20L);
        pending.put(playerId, new PendingHandshake(nonce, timeoutTask));

        try {
            player.sendPluginMessage(plugin, CHANNEL,
                    CompanionHandshakeProtocol.encodeChallenge(nonce));
        } catch (RuntimeException exception) {
            // Keep the timeout active: inability to deliver the challenge must fail closed.
            plugin.getLogger().warning("Tidak dapat mengirim companion challenge ke "
                    + player.getName() + ": " + exception.getMessage());
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (closed || !CHANNEL.equals(channel) || player == null) return;

        UUID playerId = player.getUniqueId();
        PendingHandshake challenge = pending.get(playerId);
        if (challenge == null) return;

        if (!CompanionHandshakeProtocol.isValidResponse(message, challenge.nonce())) {
            plugin.getLogger().fine("Companion handshake tidak valid dari " + player.getName()
                    + "; menunggu timeout.");
            return;
        }

        pending.remove(playerId);
        challenge.timeoutTask().cancel();
        plugin.getLogger().fine("ArenaSystem companion terverifikasi untuk " + player.getName()
                + " (protocol " + CompanionHandshakeProtocol.PROTOCOL_VERSION + ").");
    }

    private void handleTimeout(UUID playerId, UUID expectedNonce) {
        PendingHandshake current = pending.get(playerId);
        if (current == null || !current.nonce().equals(expectedNonce)) return;

        pending.remove(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) return;

        player.kickPlayer(ChatColor.RED + "[ArenaSystem] Companion client mod wajib belum terdeteksi.\n"
                + ChatColor.YELLOW + "Pasang Fabric API dan ArenaSystem Client Mod, lalu masuk kembali.");
    }

    private void cancelScheduledChallenge(UUID playerId) {
        BukkitTask scheduled = scheduledChallenges.remove(playerId);
        if (scheduled != null) scheduled.cancel();
    }

    private void cancelPendingHandshake(UUID playerId) {
        PendingHandshake previous = pending.remove(playerId);
        if (previous != null) previous.timeoutTask().cancel();
    }

    void shutdown() {
        if (closed) return;
        closed = true;

        for (BukkitTask task : scheduledChallenges.values()) task.cancel();
        scheduledChallenges.clear();

        for (PendingHandshake handshake : pending.values()) {
            handshake.timeoutTask().cancel();
        }
        pending.clear();

        Messenger messenger = plugin.getServer().getMessenger();
        messenger.unregisterIncomingPluginChannel(plugin, CHANNEL, this);
        messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL);
    }
}
