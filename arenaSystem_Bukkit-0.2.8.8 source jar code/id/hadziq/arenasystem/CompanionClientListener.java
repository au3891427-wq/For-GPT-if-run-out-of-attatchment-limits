package id.hadziq.arenasystem;

import java.util.Arrays;
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

/**
 * Enforces the companion client admission gate.
 *
 * The handshake follows an SVC-inspired staged flow:
 * protocol negotiation -> per-session secret -> client proof -> server challenge
 * -> client connection check -> acknowledgement.
 */
final class CompanionClientListener implements Listener, PluginMessageListener {
    static final String CHANNEL = "arenasystem:mod_handshake";

    private static final String BYPASS_PERMISSION = "arenasystem.client.bypass";
    private static final long CHALLENGE_DELAY_TICKS = 2L;

    private final JavaPlugin plugin;
    private final Map<UUID, PendingHandshake> pending = new HashMap<>();
    private final Map<UUID, BukkitTask> scheduledChallenges = new HashMap<>();
    private final Set<UUID> gatedPlayers = new HashSet<>();
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

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        scheduleChallenge(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        cancelScheduledChallenge(playerId);
        cancelPendingHandshake(playerId);
        gatedPlayers.remove(playerId);
    }

    private void scheduleChallenge(Player player) {
        if (closed || isBypassed(player)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        cancelScheduledChallenge(playerId);
        cancelPendingHandshake(playerId);

        gatedPlayers.add(playerId);
        player.setGameMode(GameMode.SPECTATOR);

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            scheduledChallenges.remove(playerId);
            if (closed || !player.isOnline()) {
                return;
            }

            if (isBypassed(player)) {
                releaseForBypass(player);
                return;
            }

            sendServerHello(player);
        }, CHALLENGE_DELAY_TICKS);
        scheduledChallenges.put(playerId, task);
    }

    private boolean isBypassed(Player player) {
        return player.isOp() || player.hasPermission(BYPASS_PERMISSION);
    }

    private void sendServerHello(Player player) {
        UUID playerId = player.getUniqueId();
        UUID sessionId = UUID.randomUUID();
        byte[] serverNonce = CompanionHandshakeProtocol.randomBytes(CompanionHandshakeProtocol.NONCE_BYTES);

        long timeoutSeconds = Math.max(
            5L,
            Math.min(60L, plugin.getConfig().getLong("client-mod.timeout-seconds", 10L))
        );
        BukkitTask timeoutTask = Bukkit.getScheduler().runTaskLater(
            plugin,
            () -> handleTimeout(playerId, sessionId),
            timeoutSeconds * 20L
        );

        PendingHandshake handshake = new PendingHandshake(sessionId, serverNonce, timeoutTask);
        pending.put(playerId, handshake);

        sendMessage(player, CompanionHandshakeProtocol.encodeServerHello(sessionId, serverNonce));
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] payload) {
        if (closed || !CHANNEL.equals(channel) || player == null || !player.isOnline()) {
            return;
        }

        PendingHandshake handshake = pending.get(player.getUniqueId());
        if (handshake == null) {
            return;
        }

        CompanionHandshakeProtocol.Message message = CompanionHandshakeProtocol.decode(payload);
        if (message == null) {
            plugin.getLogger().fine("[ClientMod] Mengabaikan paket handshake malformed dari " + player.getName() + ".");
            return;
        }

        if (!handshake.sessionId.equals(message.sessionId())) {
            plugin.getLogger().fine("[ClientMod] Mengabaikan paket dari sesi lama milik " + player.getName() + ".");
            return;
        }

        if (message.protocolVersion() != CompanionHandshakeProtocol.PROTOCOL_VERSION) {
            rejectIncompatibleClient(player, handshake, message.protocolVersion());
            return;
        }

        switch (message.type()) {
            case CLIENT_HELLO -> handleClientHello(player, handshake, message.body());
            case AUTHENTICATE -> handleAuthenticate(player, handshake, message.body());
            case CONNECTION_CHECK -> handleConnectionCheck(player, handshake, message.body());
            default -> plugin.getLogger().fine(
                "[ClientMod] Mengabaikan pesan " + message.type() + " yang tidak sesuai arah dari " + player.getName() + "."
            );
        }
    }

    private void handleClientHello(Player player, PendingHandshake handshake, byte[] body) {
        if (handshake.stage != Stage.WAITING_CLIENT_HELLO || body.length != CompanionHandshakeProtocol.NONCE_BYTES * 2) {
            return;
        }

        byte[] echoedServerNonce = Arrays.copyOfRange(body, 0, CompanionHandshakeProtocol.NONCE_BYTES);
        if (!CompanionHandshakeProtocol.constantTimeEquals(handshake.serverNonce, echoedServerNonce)) {
            plugin.getLogger().fine("[ClientMod] CLIENT_HELLO tidak cocok dengan challenge aktif dari " + player.getName() + ".");
            return;
        }

        handshake.clientNonce = Arrays.copyOfRange(body, CompanionHandshakeProtocol.NONCE_BYTES, body.length);
        handshake.sessionSecret = CompanionHandshakeProtocol.randomBytes(CompanionHandshakeProtocol.SECRET_BYTES);
        handshake.authNonce = CompanionHandshakeProtocol.randomBytes(CompanionHandshakeProtocol.NONCE_BYTES);
        handshake.stage = Stage.WAITING_AUTHENTICATE;

        sendMessage(
            player,
            CompanionHandshakeProtocol.encodeAuthChallenge(
                handshake.sessionId,
                handshake.sessionSecret,
                handshake.authNonce
            )
        );
    }

    private void handleAuthenticate(Player player, PendingHandshake handshake, byte[] body) {
        if (handshake.stage != Stage.WAITING_AUTHENTICATE || body.length != CompanionHandshakeProtocol.PROOF_BYTES) {
            return;
        }

        byte[] expectedProof = CompanionHandshakeProtocol.createClientAuthProof(
            handshake.sessionSecret,
            handshake.sessionId,
            handshake.serverNonce,
            handshake.clientNonce,
            handshake.authNonce
        );
        if (!CompanionHandshakeProtocol.constantTimeEquals(expectedProof, body)) {
            plugin.getLogger().fine("[ClientMod] AUTHENTICATE tidak valid dari " + player.getName() + ".");
            return;
        }

        handshake.checkNonce = CompanionHandshakeProtocol.randomBytes(CompanionHandshakeProtocol.NONCE_BYTES);
        handshake.clientCheckProof = CompanionHandshakeProtocol.createClientCheckProof(
            handshake.sessionSecret,
            handshake.sessionId,
            handshake.serverNonce,
            handshake.clientNonce,
            handshake.authNonce,
            handshake.checkNonce
        );
        byte[] serverProof = CompanionHandshakeProtocol.createServerChallengeProof(
            handshake.sessionSecret,
            handshake.sessionId,
            handshake.serverNonce,
            handshake.clientNonce,
            handshake.authNonce,
            handshake.checkNonce
        );
        handshake.stage = Stage.WAITING_CONNECTION_CHECK;

        sendMessage(
            player,
            CompanionHandshakeProtocol.encodeServerChallenge(
                handshake.sessionId,
                handshake.checkNonce,
                serverProof
            )
        );
    }

    private void handleConnectionCheck(Player player, PendingHandshake handshake, byte[] body) {
        if (handshake.stage != Stage.WAITING_CONNECTION_CHECK || body.length != CompanionHandshakeProtocol.PROOF_BYTES) {
            return;
        }

        if (!CompanionHandshakeProtocol.constantTimeEquals(handshake.clientCheckProof, body)) {
            plugin.getLogger().fine("[ClientMod] CONNECTION_CHECK tidak valid dari " + player.getName() + ".");
            return;
        }

        byte[] serverAckProof = CompanionHandshakeProtocol.createServerAckProof(
            handshake.sessionSecret,
            handshake.sessionId,
            handshake.serverNonce,
            handshake.clientNonce,
            handshake.authNonce,
            handshake.checkNonce,
            handshake.clientCheckProof
        );

        pending.remove(player.getUniqueId());
        handshake.timeoutTask.cancel();
        gatedPlayers.remove(player.getUniqueId());

        GameMode defaultGameMode = plugin.getServer().getDefaultGameMode();
        player.setGameMode(defaultGameMode);

        sendMessage(player, CompanionHandshakeProtocol.encodeConnectionAck(handshake.sessionId, serverAckProof));

        plugin.getLogger().info(
            "[ClientMod] " + player.getName()
                + " berhasil menyelesaikan handshake protocol v"
                + CompanionHandshakeProtocol.PROTOCOL_VERSION
                + "; gamemode dikembalikan ke default server (" + defaultGameMode + ")."
        );
    }

    private void rejectIncompatibleClient(Player player, PendingHandshake handshake, int clientVersion) {
        pending.remove(player.getUniqueId());
        handshake.timeoutTask.cancel();

        plugin.getLogger().warning(
            "[ClientMod] " + player.getName() + " memakai protocol " + clientVersion
                + ", sedangkan server membutuhkan protocol "
                + CompanionHandshakeProtocol.PROTOCOL_VERSION + "."
        );
        player.kickPlayer(
            ChatColor.RED + "[ArenaSystem] Versi companion client tidak kompatibel.\n"
                + ChatColor.YELLOW + "Perbarui ArenaSystem Client Mod, lalu masuk kembali."
        );
    }

    private void releaseForBypass(Player player) {
        UUID playerId = player.getUniqueId();
        cancelPendingHandshake(playerId);
        gatedPlayers.remove(playerId);
        player.setGameMode(plugin.getServer().getDefaultGameMode());
        plugin.getLogger().info("[ClientMod] " + player.getName() + " melewati verifikasi melalui permission bypass.");
    }

    private void sendMessage(Player player, byte[] payload) {
        try {
            player.sendPluginMessage(plugin, CHANNEL, payload);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning(
                "[ClientMod] Gagal mengirim pesan handshake ke " + player.getName() + ": " + exception.getMessage()
            );
        }
    }

    private void handleTimeout(UUID playerId, UUID sessionId) {
        PendingHandshake handshake = pending.get(playerId);
        if (handshake == null || !handshake.sessionId.equals(sessionId)) {
            return;
        }

        pending.remove(playerId);
        gatedPlayers.remove(playerId);

        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            plugin.getLogger().warning(
                "[ClientMod] " + player.getName() + " ditolak karena handshake protocol v"
                    + CompanionHandshakeProtocol.PROTOCOL_VERSION + " tidak selesai sebelum timeout."
            );
            player.kickPlayer(
                ChatColor.RED + "[ArenaSystem] Verifikasi client mod gagal.\n"
                    + ChatColor.YELLOW + "Pasang atau perbarui Fabric API dan ArenaSystem Client Mod, lalu masuk kembali."
            );
        }
    }

    private void cancelScheduledChallenge(UUID playerId) {
        BukkitTask task = scheduledChallenges.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    private void cancelPendingHandshake(UUID playerId) {
        PendingHandshake handshake = pending.remove(playerId);
        if (handshake != null) {
            handshake.timeoutTask.cancel();
        }
    }

    private boolean isGated(Player player) {
        return gatedPlayers.contains(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPendingMove(PlayerMoveEvent event) {
        if (isGated(event.getPlayer()) && event.getTo() != null) {
            Location from = event.getFrom();
            Location to = event.getTo();
            boolean positionChanged = from.getWorld() != to.getWorld()
                || from.getX() != to.getX()
                || from.getY() != to.getY()
                || from.getZ() != to.getZ();
            if (positionChanged) {
                event.setTo(new Location(from.getWorld(), from.getX(), from.getY(), from.getZ(), to.getYaw(), to.getPitch()));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPendingCommand(PlayerCommandPreprocessEvent event) {
        if (isGated(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPendingInteract(PlayerInteractEvent event) {
        if (isGated(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPendingEntityInteract(PlayerInteractEntityEvent event) {
        if (isGated(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    void shutdown() {
        if (closed) {
            return;
        }

        closed = true;

        for (BukkitTask task : scheduledChallenges.values()) {
            task.cancel();
        }
        scheduledChallenges.clear();

        for (PendingHandshake handshake : pending.values()) {
            handshake.timeoutTask.cancel();
        }
        pending.clear();

        GameMode defaultGameMode = plugin.getServer().getDefaultGameMode();
        for (UUID playerId : new HashSet<>(gatedPlayers)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.setGameMode(defaultGameMode);
            }
        }
        gatedPlayers.clear();

        Messenger messenger = plugin.getServer().getMessenger();
        messenger.unregisterIncomingPluginChannel(plugin, CHANNEL, this);
        messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL);
    }

    private enum Stage {
        WAITING_CLIENT_HELLO,
        WAITING_AUTHENTICATE,
        WAITING_CONNECTION_CHECK
    }

    private static final class PendingHandshake {
        private final UUID sessionId;
        private final byte[] serverNonce;
        private final BukkitTask timeoutTask;
        private Stage stage = Stage.WAITING_CLIENT_HELLO;
        private byte[] clientNonce;
        private byte[] sessionSecret;
        private byte[] authNonce;
        private byte[] checkNonce;
        private byte[] clientCheckProof;

        private PendingHandshake(UUID sessionId, byte[] serverNonce, BukkitTask timeoutTask) {
            this.sessionId = sessionId;
            this.serverNonce = serverNonce.clone();
            this.timeoutTask = timeoutTask;
        }
    }
}
