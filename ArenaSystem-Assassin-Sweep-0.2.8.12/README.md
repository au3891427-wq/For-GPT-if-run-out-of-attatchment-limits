# ArenaSystem Assassin Off-hand — Source Bundle

This bundle contains two independently built projects for Minecraft/Paper 26.3. Both use Java 25.

- `Server/`: Paper plugin 0.2.8.13, built with Maven.
- `Client/`: Fabric companion mod 0.2.4, built with Gradle 9.8.0.

## Build the server plugin

Open PowerShell in `Server`:

```powershell
mvn --batch-mode clean package
```

Output: `Server/target/arenaSystem_Bukkit-0.2.8.13.jar`. Install it in the Paper server's `plugins` folder.

## Build the Fabric client mod

Open PowerShell in `Client`:

```powershell
gradle --no-daemon clean build
```

Output: `Client/build/libs/ArenaSystem_Client-0.2.4.jar`. Install it in each player's `mods` folder. Remove older ArenaSystem client JARs first; keep only one.

## Assassin Twin Blade

- Right-click with valid Assassin blades in both hands to activate the off-hand strike; no standalone off-hand keybind is used.
- Right-clicking air performs a short ray-traced strike; right-clicking a player targets that player directly when they are an enemy within range and line of sight.
- Valid use requires an active TDM match, Assassin class, Survival mode, and valid ArenaSystem Assassin blades in both hands.
- A successful target hit applies 2.0 additional damage.
- Each accepted use triggers a server-authoritative 12-tick (600 ms) cooldown.
- Server sends cooldown duration through `arenasystem:assassin_offhand_cooldown`.
- Client renders the vanilla sword-shaped attack-indicator sprite above the crosshair with yellow bottom-up progress. The vanilla main-hand attack indicator below the crosshair is unchanged.

Paper remains authoritative for validation, damage, and cooldown. The client only renders the server-sent cooldown.

## Verification

GitHub Actions compiles both projects and runs the existing companion-handshake and class-skill input protocol tests. A live in-game Paper/Fabric test is still required before production deployment.
