# ArenaSystem Paper — 0.2.8.12

Based on the supplied ArenaSystem Bukkit 0.2.8.11 source directory. Existing server systems are retained; this update replaces the standalone off-hand click path with a server-authoritative vanilla sweep path.

## Build

Requirements: JDK 25, Maven 3.9+, and internet access to resolve the Paper 26.3 API.

Run from this directory:

```powershell
mvn --batch-mode clean package
```

Output: `target/arenaSystem_Bukkit-0.2.8.12.jar`.

Paper API is a `provided` dependency and is not bundled into the plugin JAR.
