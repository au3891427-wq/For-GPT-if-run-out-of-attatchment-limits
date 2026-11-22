package id.hadziq.arenasystem.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

/**
 * Registers the "Use ArenaSystem Skill" keybind and wires it to the same
 * client-side action Minecraft already performs for the vanilla "Use Item"
 * input (a normal main-hand item-use interaction).
 *
 * <p>This intentionally does NOT introduce any new network payload or
 * custom protocol: pressing the key simply triggers
 * {@link net.minecraft.client.multiplayer.MultiPlayerGameMode#useItem}
 * exactly as a right-click would, which is the same vanilla
 * {@code ServerboundUseItemPacket} the ArenaSystem server plugin's existing
 * {@code PlayerInteractEvent} (RIGHT_CLICK_AIR / RIGHT_CLICK_BLOCK) listener
 * already understands for activating a held class-skill item (see
 * {@code ClassAbilityListener#onSkillUse} in the ArenaSystem server plugin).
 * The key is therefore a convenience alias for "use the item in my main
 * hand", nothing more, and requires zero changes to the server plugin.
 *
 * <p>The binding is a regular {@link KeyMapping}; input code is never
 * hardcoded into the trigger logic below (only used once, as the optional
 * default, which the player can freely rebind from
 * Options -> Controls -> Key Binds -> ArenaSystem).
 */
final class ArenaSystemKeybinds {
    private static final String CATEGORY = "key.categories.arenasystem";
    private static final String TRANSLATION_KEY = "key.arenasystem_client.use_skill";

    private static KeyMapping useSkillKey;

    private ArenaSystemKeybinds() {
    }

    static void initialize() {
        useSkillKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                TRANSLATION_KEY,
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(ArenaSystemKeybinds::onEndClientTick);
    }

    private static void onEndClientTick(Minecraft client) {
        while (useSkillKey.consumeClick()) {
            triggerSkillUse(client);
        }
    }

    private static void triggerSkillUse(Minecraft client) {
        if (client.player == null || client.level == null || client.gameMode == null) {
            // No active game session yet; nothing to activate.
            return;
        }
        if (client.screen != null) {
            // Avoid activating while chat, inventory, or any other GUI is open,
            // matching vanilla's own suppression of game inputs behind a screen.
            return;
        }

        LocalPlayer player = client.player;
        InteractionResult result = client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        if (result != null && result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
        }
    }
}
