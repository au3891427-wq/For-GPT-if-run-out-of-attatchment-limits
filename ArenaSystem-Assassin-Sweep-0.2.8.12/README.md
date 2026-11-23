# ArenaSystem Assassin Sweep — Source Bundle

This bundle contains two independently built projects for Minecraft/Paper 26.3. Both use Java 25.

- `Server/`: Paper plugin 0.2.8.12, built with Maven.
- `Client/`: Fabric companion mod 0.2.3, built with Gradle 9.8.0.

## Build the server plugin

Open PowerShell in `Server`:

```powershell
mvn --batch-mode clean package
```

Output: `Server/target/arenaSystem_Bukkit-0.2.8.12.jar`. Install it in the Paper server's `plugins` folder.

## Build the Fabric client mod

Open PowerShell in `Client`:

```powershell
gradle --no-daemon clean build
```

Output: `Client/build/libs/ArenaSystem_Client-0.2.3.jar`. Install it in each player's `mods` folder. Remove older ArenaSystem client JARs first; keep only one.

## Assassin Twin Blade

- Standalone off-hand keybind and right-click activation are removed.
- Server reacts only to a real `ENTITY_SWEEP_ATTACK` damage event.
- Attacker must be in a running TDM match, use Assassin, be in Survival, and hold valid ArenaSystem Assassin blades in both hands.
- Each opposing player hit by the vanilla sweep receives an additional 2.0 damage.
- Targets from one sweep tick receive the bonus together, with one 12-tick (600 ms) off-hand cooldown.
- Server sends cooldown duration through `arenasystem:assassin_offhand_cooldown`.
- Client draws a yellow off-hand recharge bar above the crosshair. Vanilla main-hand attack indicator remains below it.

Paper remains authoritative for validation, damage, and cooldown. The client only renders the server-sent cooldown.

## Verification

GitHub Actions compiles both projects and runs the existing companion-handshake and class-skill input protocol tests. A live in-game Paper/Fabric test is still required before production deployment.
