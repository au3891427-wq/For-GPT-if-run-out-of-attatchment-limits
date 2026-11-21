package id.hadziq.arenasystem;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.scheduler.BukkitTask;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Verifies that the ArenaSystem companion Fabric client mod is active and speaks
 * the expected handshake protocol. A missing response is reported as unverified,
 * not treated as proof that a player deliberately has no mod.
 */
final class ClientModPresenceListener implements Listener, PluginMessageListener {
    static final String CHANNEL = "arenasystem:mod_handshake";
    static final int PROTOCOL_VERSION = 1;

    private static final long REGISTRATION_POLL_PERIOD_TICKS = 10L;
    private static final int MAX_REGISTRATION_POLLS = 10;
    private static final long RESPONSE_TIMEOUT_TICKS = 60L;

    private final ArenaSystemPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();

    ClientModPresenceListener(ArenaSystemPlugin plugin) {
        this.plugin = plugin;
    }

    void registerChannels() {
        Messenger messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, CHANNEL);
        messenger.registerIncomingPluginChannel(plugin, CHANNEL, this);
    }

    void close() {
        for (Session session : sessions.values()) {
            cancel(session.registrationTask);
            cancel(session.responseTimeoutTask);
        }
        sessions.clear();

        Messenger messenger = plugin.getServer().getMessenger();
        messenger.unregisterIncomingPluginChannel(plugin, CHANNEL);
        messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        Session previous = sessions.remove(playerId);
        if (previous != null) {
            cancel(previous.registrationTask);
            cancel(previous.responseTimeoutTask);
        }

        Session session = new Session(UUID.randomUUID());
        sessions.put(playerId, session);

        // Wait for the client's minecraft:register packet before sending. Paper
        // drops outgoing plugin messages for channels the client has not registered.
        session.registrationTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                () -> trySendChallenge(player, session),
                1L,
                REGISTRATION_POLL_PERIOD_TICKS
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Session session = sessions.remove(event.getPlayer().getUniqueId());
        if (session == null) return;

        cancel(session.registrationTask);
        cancel(session.responseTimeoutTask);
    }

    private void trySendChallenge(Player player, Session session) {
        if (!player.isOnline() || sessions.get(player.getUniqueId()) != session) {
            cancel(session.registrationTask);
            return;
        }

        session.registrationPolls++;
        if (player.getListeningPluginChannels().contains(CHANNEL)) {
            cancel(session.registrationTask);
            session.registrationTask = null;

            ByteBuffer payload = ByteBuffer.allocate(Long.BYTES * 2);
            payload.putLong(session.nonce.getMostSignificantBits());
            payload.putLong(session.nonce.getLeastSignificantBits());

            player.sendPluginMessage(plugin, CHANNEL, payload.array());
            session.awaitingResponse = true;
            session.responseTimeoutTask = Bukkit.getScheduler().runTaskLater(
                    plugin,
                    () -> finishWithoutResponse(player, session),
                    RESPONSE_TIMEOUT_TICKS
            );
            return;
        }

        if (session.registrationPolls >= MAX_REGISTRATION_POLLS) {
            finish(player, session, Result.NOT_REGISTERED,
                    "client mod channel was not registered");
        }
    }

    private void finishWithoutResponse(Player player, Session session) {
        if (sessions.get(player.getUniqueId()) != session || !session.awaitingResponse) return;
        finish(player, session, Result.NO_RESPONSE,
                "client registered the channel but did not complete the handshake");
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel) || message == null) return;

        Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.awaitingResponse) return;

        // Response format: protocol version (int), followed by the 128-bit nonce.
        if (message.length != Integer.BYTES + Long.BYTES * 2) {
            return;
        }

        ByteBuffer payload = ByteBuffer.wrap(message);
        int protocolVersion = payload.getInt();
        UUID nonce = new UUID(payload.getLong(), payload.getLong());

        // Ignore stale or unsolicited replies. The random nonce binds the reply
        // to the challenge issued for this player's current connection.
        if (!session.nonce.equals(nonce)) return;

        if (protocolVersion != PROTOCOL_VERSION) {
            finish(player, session, Result.INCOMPATIBLE,
                    "client protocol " + protocolVersion + ", server protocol " + PROTOCOL_VERSION);
            return;
        }

        finish(player, session, Result.VERIFIED,
                "companion mod handshake verified (protocol " + protocolVersion + ")");
    }

    private void finish(Player player, Session session, Result result, String detail) {
        if (sessions.get(player.getUniqueId()) != session) return;

        session.result = result;
        session.awaitingResponse = false;
        cancel(session.registrationTask);
        cancel(session.responseTimeoutTask);
        session.registrationTask = null;
        session.responseTimeoutTask = null;

        boolean enforcementEnabled = plugin.getConfig().getBoolean("client-mod.required", true);
        boolean bypass = player.hasPermission("arenasystem.clientmod.bypass");
        if (result != Result.VERIFIED && enforcementEnabled && !bypass) {
            String kickMessage = switch (result) {
                case NOT_REGISTERED -> ChatColor.RED
                        + "[ArenaSystem] Companion client mod wajib belum terdeteksi.\\n"
                        + ChatColor.YELLOW + "Pasang Fabric API dan ArenaSystem Client Mod, lalu masuk kembali.";
                case NO_RESPONSE -> ChatColor.RED
                        + "[ArenaSystem] Verifikasi companion client mod gagal.\\n"
                        + ChatColor.YELLOW + "Periksa versi Fabric API dan ArenaSystem Client Mod.";
                case INCOMPATIBLE -> ChatColor.RED
                        + "[ArenaSystem] Versi companion client mod tidak kompatibel.\\n"
                        + ChatColor.YELLOW + "Perbarui ArenaSystem Client Mod agar sesuai dengan server.";
                case VERIFIED, PENDING -> throw new IllegalStateException("Unexpected rejection result: " + result);
            };

            plugin.getLogger().warning("[ClientMod] " + player.getName()
                    + " ditolak: " + result + " (" + detail + ").");
            player.kickPlayer(kickMessage);
            return;
        }

        if (result == Result.VERIFIED) {
            player.sendMessage(ChatColor.GREEN
                    + "[ArenaSystem] Companion client mod terdeteksi dan kompatibel.");
        } else {
            switch (result) {
                case NOT_REGISTERED -> player.sendMessage(ChatColor.YELLOW
                        + "[ArenaSystem] Companion client mod belum terdeteksi.");
                case NO_RESPONSE -> player.sendMessage(ChatColor.YELLOW
                        + "[ArenaSystem] Verifikasi client mod tidak selesai. Periksa versi mod/Fabric API.");
                case INCOMPATIBLE -> player.sendMessage(ChatColor.RED
                        + "[ArenaSystem] Versi companion client mod tidak kompatibel.");
                case VERIFIED, PENDING -> {
                    // No action required for these states.
                }
            }
        }

        if (result != Result.VERIFIED && bypass) {
            plugin.getLogger().info("[ClientMod] " + player.getName()
                    + " gagal verifikasi tetapi diizinkan oleh arenasystem.clientmod.bypass.");
        }

        plugin.getLogger().info("[ClientMod] " + player.getName() + ": " + result + " (" + detail + ").");
    }

    private static void cancel(BukkitTask task) {
        if (task != null) task.cancel();
    }

    private enum Result {
        PENDING,
        VERIFIED,
        NOT_REGISTERED,
        NO_RESPONSE,
        INCOMPATIBLE
    }

    private static final class Session {
        private final UUID nonce;
        private int registrationPolls;
        private boolean awaitingResponse;
        private Result result = Result.PENDING;
        private BukkitTask registrationTask;
        private BukkitTask responseTimeoutTask;

        private Session(UUID nonce) {
            this.nonce = nonce;
        }
    }
}
