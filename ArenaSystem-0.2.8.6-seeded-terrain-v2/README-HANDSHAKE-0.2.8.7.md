# ArenaSystem 0.2.8.7 — Seeded Terrain v2 + Companion Handshake

This source is based on the latest `ArenaSystem-0.2.8.6-seeded-terrain-v2` project and bumps the Paper plugin to 0.2.8.7 to add the required Fabric companion handshake.

## Build

Requirements: JDK 25 and Apache Maven.

From this directory:

```powershell
mvn --no-transfer-progress clean package
```

Expected JAR:

```
target\arenaSystem_Bukkit-0.2.8.7.jar
```

## Companion client requirements

The default configuration requires the ArenaSystem Fabric client. The matching protocol is channel `arenasystem:mod_handshake`:

- Paper sends a 16-byte UUID challenge.
- Fabric replies with a 20-byte message: 4-byte protocol version (`1`) followed by the 16-byte UUID challenge.
- The response must echo the nonce for the current connection before the timeout expires.

Defaults:

```yaml
client-mod:
  required: true
  timeout-seconds: 10
```

Timeout is clamped to 5–60 seconds. Set `client-mod.required: false` to disable the requirement. OPs and players with `arenasystem.client.bypass` bypass the check so administrators can recover access if the companion mod is unavailable.

The handshake is a version/presence check, not cryptographic attestation or anti-cheat. Keep all gameplay authority and validation on the Paper server.

## Client package

Use the matching Fabric 26.3 companion build with Fabric Loader 0.20.1+, Fabric API 0.166.0+26.3+, and Java 25+. The versioned client packaging patch names the artifact `arenaSystem_client-0.1.1.jar`; remove old ArenaSystem client JARs from `.minecraft/mods` before installing it.

The updater helper is not a Fabric mod and must not be placed in `mods`.

## Validation status

See [VALIDATION_REPORT-0.2.8.7.md](VALIDATION_REPORT-0.2.8.7.md). Protocol smoke tests and a stub-based source compilation passed. A complete Paper 26.3 build and live client/server test remain mandatory before production deployment.
