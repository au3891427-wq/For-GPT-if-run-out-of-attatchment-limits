# ArenaSystem 0.2.8.7 — Companion Handshake Validation

## Source baseline

This patch is based on the exact `ArenaSystem-0.2.8.6-seeded-terrain-v2` directory from the repository. The terrain implementation and its existing tests are retained; no terrain-generation classes were changed.

## Changes

- Bumps the Paper plugin artifact to `arenaSystem_Bukkit-0.2.8.7.jar`.
- Adds the Paper-side `arenasystem:mod_handshake` incoming/outgoing plugin-message channel.
- Sends a 16-byte, big-endian UUID challenge shortly after player join.
- Expects a 20-byte response: protocol version `1` (big-endian int) followed by the exact challenge UUID (two big-endian longs).
- Rejects malformed, wrong-version, stale, or mismatched-nonce responses. Players who do not answer within the configured timeout are disconnected with the companion-mod message.
- Adds `client-mod.required` (default `true`) and `client-mod.timeout-seconds` (default `10`, runtime clamp `5..60`).
- Adds `arenasystem.client.bypass` (default OP) for administrative recovery/testing.
- Cancels pending challenges and unregisters plugin channels on quit/disable.

## Wire compatibility

The matching Fabric client is expected to register `arenasystem:mod_handshake`, read exactly 16 UUID bytes from the server challenge, then send protocol version `1` plus the same UUID. The Fabric client build from PR #2 in `proyek-arenasystem-plugin` uses this layout.

Paper's plugin-message API sends to clients that have advertised the channel. Fabric's ClientPlayNetworking global receiver participates in that registration flow. The actual Paper/Fabric connection still needs a runtime test.

## Validation performed

- Standalone Java protocol smoke tests: passed (challenge length, byte order, nonce echo, protocol mismatch, stale nonce, malformed lengths, null input).
- New listener and protocol sources: syntax-compiled with JDK 21 against minimal Bukkit/Paper API stubs matching the documented method signatures.
- Source integration markers inspected: enable registration, disable cleanup, config defaults, plugin descriptor permission, and version metadata are present.
- Terrain implementation files were not edited.

## Not performed / limitations

- Full Maven build against Paper 26.3 was not run in this environment: only JDK 21 is installed; Maven is unavailable; the project targets Java 25.
- No live Paper 26.3 + Fabric 26.3 connection test was run.
- The handshake confirms protocol participation, not cryptographic client authenticity. A modified client can imitate this packet; server-side gameplay validation remains authoritative.
- The companion client JAR must be the matching Fabric client build (version 0.1.1) and must include Fabric API. The versioned artifact name expected by the latest client packaging patch is `arenaSystem_client-0.1.1.jar`.

## Deployment gate

Do not deploy to the live server until the JDK 25 Maven build succeeds and the following are tested on a backup server:

1. A player with the matching Fabric API + ArenaSystem Client Mod joins successfully.
2. A player without the client mod is disconnected after the timeout.
3. A player with an outdated/wrong protocol client is disconnected.
4. An OP / player with `arenasystem.client.bypass` can join for recovery.
5. Existing seeded terrain v2 generation, regeneration, snapshot recovery, and match flows remain unchanged.
