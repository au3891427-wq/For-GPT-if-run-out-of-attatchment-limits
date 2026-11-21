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

    /**
     * Paper silently drops outgoing plugin messages when the client has not
     * registered the channel yet. Wait until Fabric advertises it instead of
     * assuming that two server ticks are enough for the client's registration
     * packet to arrive.
     */
    private void scheduleChallenge(Player player) {
        if (closed || isBypassed(player)) return;

        UUID playerId = player.getUniqueId();
        cancelScheduledChallenge(playerId);
        cancelPendingHandshake(playerId);

        if (hasRegisteredChannel(player)) {
            sendChallenge(player);
            return;
        }

        long timeoutTicks = getTimeoutSeconds() * 20L;
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private long waitedTicks;

            @Override
            public void run() {
                if (closed || !player.isOnline() || isBypassed(player)) {
                    cancelScheduledChallenge(playerId);
                    return;
                }

                if (hasRegisteredChannel(player)) {
                    cancelScheduledChallenge(playerId);
                    plugin.getLogger().info("Companion channel registered by "
                            + player.getName() + "; sending handshake challenge.");
                    sendChallenge(player);
                    return;
                }

                waitedTicks++;
                if (waitedTicks >= timeoutTicks) {
                    cancelScheduledChallenge(playerId);
                    kickForMissingClient(player);
                }
            }
        }, 1L, 1L);
        scheduledChallenges.put(playerId, task);
    }

    private boolean hasRegisteredChannel(Player player) {
        return player.getListeningPluginChannels().contains(CHANNEL);
    }

    private long getTimeoutSeconds() {
        return Math.max(5L,
                Math.min(60L, plugin.getConfig().getLong("client-mod.timeout-seconds", 10L)));
    }

    private boolean isBypassed(Player player) {
        return player.isOp() || player.hasPermission(BYPASS_PERMISSION);
    }

    private void sendChallenge(Player player) {
        if (closed || !player.isOnline() || isBypassed(player)) return;

        // Guard against a lost/unregistered channel between the readiness check
        // and the send. Never start a response timeout for a packet Paper drops.
        if (!hasRegisteredChannel(player)) {
            scheduleChallenge(player);
            return;
        }

        UUID playerId = player.getUniqueId();
        UUID nonce = UUID.randomUUID();
        long timeoutSeconds = getTimeoutSeconds();

        BukkitTask timeoutTask = Bukkit.getScheduler().runTaskLater(plugin,
                () -> handleTimeout(playerId, nonce), timeoutSeconds * 20L);
        pending.put(playerId, new PendingHandshake(nonce, timeoutTask));

        try {
            player.sendPluginMessage(plugin, CHANNEL,
                    CompanionHandshakeProtocol.encodeChallenge(nonce));
            plugin.getLogger().info("Sent ArenaSystem companion challenge to " + player.getName() + ".");
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
            plugin.getLogger().warning("Invalid ArenaSystem companion handshake response from "
                    + player.getName() + ".");
            return;
        }

        pending.remove(playerId);
        challenge.timeoutTask().cancel();
        plugin.getLogger().info("ArenaSystem companion handshake accepted for "
                + player.getName() + " (protocol "
                + CompanionHandshakeProtocol.PROTOCOL_VERSION + ").");
    }

    private void handleTimeout(UUID playerId, UUID expectedNonce) {
        PendingHandshake current = pending.get(playerId);
        if (current == null || !current.nonce().equals(expectedNonce)) return;

        pending.remove(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) return;

        plugin.getLogger().warning("ArenaSystem companion handshake timed out for "
                + player.getName() + ".");
        kickForMissingClient(player);
    }

    private void kickForMissingClient(Player player) {
        if (!player.isOnline()) return;
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
