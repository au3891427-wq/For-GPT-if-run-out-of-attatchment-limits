package id.hadziq.arenasystem;

import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * Receives explicit G/H class-skill input messages from ArenaSystem Fabric clients.
 */
final class SkillInputListener implements PluginMessageListener {
   private final ArenaSystemPlugin plugin;

   SkillInputListener(ArenaSystemPlugin plugin) {
      this.plugin = plugin;
      plugin.getServer().getMessenger().registerIncomingPluginChannel(
         plugin,
         SkillInputProtocol.CHANNEL,
         this
      );
      plugin.getServer().getMessenger().registerOutgoingPluginChannel(
         plugin,
         SkillInputProtocol.CHANNEL
      );
   }

   @Override
   public void onPluginMessageReceived(String channel, Player player, byte[] message) {
      if (!SkillInputProtocol.CHANNEL.equals(channel)
         || player == null
         || !player.isOnline()) {
         return;
      }

      byte action = SkillInputProtocol.decodeAction(message);
      if (action == 0) {
         return;
      }

      ClassAbilityListener abilities = this.plugin.classAbilityListener;
      if (abilities != null) {
         abilities.handleSkillInput(player, action);
      }
   }

   void close() {
      this.plugin.getServer().getMessenger().unregisterIncomingPluginChannel(
         this.plugin,
         SkillInputProtocol.CHANNEL,
         this
      );
      this.plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(
         this.plugin,
         SkillInputProtocol.CHANNEL
      );
   }
}
