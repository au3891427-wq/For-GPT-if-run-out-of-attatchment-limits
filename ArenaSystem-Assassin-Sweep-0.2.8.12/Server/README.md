# ArenaSystem Paper — 0.2.8.14

Based on the supplied ArenaSystem Bukkit 0.2.8.11 source directory. Assassin off-hand activation now arrives through the Fabric client's action 3 on the existing `arenasystem:skill_input` plugin channel. Paper remains authoritative for class/match/item checks, target ray tracing, damage, animation and the 600 ms cooldown.

The old server-side right-click activation handlers have been removed to prevent duplicate activation. Ordinary right-click block interactions remain vanilla-controlled.

## Build

Requirements: JDK 25, Maven 3.9+, and internet access to resolve the Paper 26.3 API.

Run from this directory:

```powershell
mvn --batch-mode clean package
```

Output: `target/arenaSystem_Bukkit-0.2.8.14.jar`.

Paper API is a `provided` dependency and is not bundled into the plugin JAR.
