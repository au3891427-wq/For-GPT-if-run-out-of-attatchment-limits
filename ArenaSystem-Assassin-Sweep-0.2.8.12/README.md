# ArenaSystem Assassin Off-hand — Source Bundle

This bundle contains the Paper plugin and Fabric companion mod for Minecraft/Paper 26.3. Both use Java 25.

- `Server/`: Paper plugin 0.2.8.14, built with Maven.
- `Client/`: Fabric companion mod 0.2.5, built with Gradle 9.8.0.

## Build the server plugin

Open PowerShell in `Server`:

```powershell
mvn --batch-mode clean package
```

Output: `Server/target/arenaSystem_Bukkit-0.2.8.14.jar`. Install it in the Paper server's `plugins` folder.

## Build the Fabric client mod

Open PowerShell in `Client`:

```powershell
gradle --no-daemon clean build
```

Output: `Client/build/libs/ArenaSystem_Client-0.2.5.jar`. Install it in each player's `mods` folder. Remove older ArenaSystem client JARs first; keep only one.

## Assassin Twin Blade

- Fabric detects a genuine right-click press over air or a player entity and sends action `3` through the existing `arenasystem:skill_input` channel.
- The client ignores block targets; vanilla chest, door, button and other block interactions remain unchanged.
- Paper verifies active TDM, Assassin class, Survival mode and valid ArenaSystem Assassin daggers in both hands.
- Paper ray-traces the target and only damages a valid enemy within range and line of sight.
- A successful strike applies 2.0 damage; accepted activations use the server-authoritative 12-tick (600 ms) cooldown.
- Paper sends cooldown duration through `arenasystem:assassin_offhand_cooldown`.
- Client renders the vanilla sword-shaped attack-indicator sprite above the crosshair with yellow bottom-up progress. The vanilla main-hand attack indicator below the crosshair is unchanged.

The client only detects input and renders the cooldown. Paper remains authoritative for all gameplay outcomes.

## Verification

GitHub Actions compiles both projects and runs client/server input protocol tests plus the existing companion-handshake test. A live in-game Paper/Fabric integration test is still required before production deployment.
