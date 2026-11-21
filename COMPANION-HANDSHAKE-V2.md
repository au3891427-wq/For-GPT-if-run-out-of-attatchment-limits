# ArenaSystem Companion Handshake Protocol v2

## Scope

Protocol v2 replaces the single UUID echo used by 0.2.8.8 with a staged, versioned handshake. The Paper plugin and Fabric companion mod must both use protocol v2.

The implementation is inspired by the flow used by Simple Voice Chat: compatibility negotiation, per-connection session credentials, an authentication exchange, a fresh challenge, a client connection check, and an acknowledgement.

## Flow

1. Paper sends `SERVER_HELLO` containing protocol version 2, a random session UUID, and a 32-byte server nonce.
2. Fabric replies with `CLIENT_HELLO`, echoing the server nonce and adding a fresh 32-byte client nonce.
3. Paper creates a 32-byte session secret and a fresh authentication nonce, then sends `AUTH_CHALLENGE`.
4. Fabric returns `AUTHENTICATE` with an HMAC-SHA256 proof over the session transcript.
5. Paper verifies the proof with constant-time comparison, creates a second challenge nonce, and sends `SERVER_CHALLENGE` with a server proof.
6. Fabric validates the server proof and sends `CONNECTION_CHECK` with its own HMAC proof.
7. Paper validates the connection check, sends `CONNECTION_ACK`, removes the admission gate, and applies the server default gamemode.

All messages use the existing `arenasystem:mod_handshake` plugin-message channel. The message body is strictly length-checked and bound to a session UUID and protocol version. The existing admission timeout applies to the complete handshake.

## Security boundary

The HMAC transcript provides session binding and prevents captured responses from being replayed into another session. It does **not** attest that a client is an unmodified official Fabric JAR: the session secret is delivered over the same Minecraft plugin-message connection, so a custom client can implement the public protocol too. This protocol is a stronger companion-mod compatibility/presence check, not an anti-cheat or trusted-client attestation system.

## Compatibility

Protocol v1 clients are incompatible with protocol v2 servers. Update the Paper plugin and Fabric client mod together. The server keeps the existing bypass permission `arenasystem.client.bypass` and the existing Spectator admission gate.
