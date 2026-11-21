# ArenaSystem 0.2.8.8 — Seeded Terrain v2 + Companion Handshake

This build is based on the exact `ArenaSystem-0.2.8.6-seeded-terrain-v2` source and retains its seeded-terrain implementation. Version 0.2.8.8 fixes a race in the required companion-client handshake.

## Build

Requirements: JDK 25 and Apache Maven.

From this directory:

```powershell
mvn --no-transfer-progress clean package
```

Expected JAR:

```
target\arenaSystem_Bukkit-0.2.8.8.jar
```

## Companion client requirements

The default configuration requires the ArenaSystem Fabric client. The matching protocol is channel `arenasystem:mod_handshake`:

- Paper sends a 16-byte UUID challenge.
- Fabric replies with a 20-byte message: 4-byte protocol version (`1`) followed by the same 16-byte UUID.
- The response must echo the nonce for the current connection before the response timeout expires.

The server now waits until the client's plugin channel is registered before sending the challenge. This avoids Paper silently dropping an outgoing plugin message when the client has not advertised that channel yet. It also logs channel registration, challenge delivery, accepted responses, and timeouts for diagnosis.

Defaults:

```yaml
client-mod:
  required: true
  timeout-seconds: 10
```

Timeout is clamped to 5–60 seconds. The same timeout controls how long the server waits for the channel registration and how long it waits for the response after sending the challenge. Set `client-mod.required: false` to disable the requirement. OPs and players with `arenasystem.client.bypass` bypass the check for administrative recovery.

## Client package

Use the matching Fabric 26.3 companion build with Fabric Loader 0.20.1+, Fabric API 0.166.0+26.3+, and Java 25+. The matching client artifact is `arenaSystem_client-0.1.1.jar`. Remove duplicate or old ArenaSystem client JARs from `.minecraft/mods` before installing it.

The updater helper is not a Fabric mod and must not be placed in `mods`.

## Validation

See [VALIDATION_REPORT-0.2.8.8.md](VALIDATION_REPORT-0.2.8.8.md). The project build and protocol smoke tests must pass. A real Paper 26.3 + Fabric 26.3 join test is still required before deploying to the live server.

The handshake is a protocol-presence check, not cryptographic client attestation. A modified client can imitate the packet; gameplay authority and validation must remain server-side.
