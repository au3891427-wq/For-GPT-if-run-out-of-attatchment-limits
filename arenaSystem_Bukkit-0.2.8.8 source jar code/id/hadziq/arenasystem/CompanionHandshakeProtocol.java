package id.hadziq.arenasystem;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Wire protocol for the ArenaSystem companion client.
 *
 * This is an SVC-inspired, staged handshake over the existing Minecraft
 * plugin-message transport. The per-session key binds proofs to one session
 * and prevents captured responses from being replayed into another session.
 *
 * Important: because the session key is delivered through the game connection,
 * this protocol verifies a compatible protocol implementation, not the
 * authenticity of an unmodified/official client binary.
 */
final class CompanionHandshakeProtocol {
    static final int PROTOCOL_VERSION = 2;

    static final int NONCE_BYTES = 32;
    static final int SECRET_BYTES = 32;
    static final int PROOF_BYTES = 32;
    static final int MAX_MESSAGE_BYTES = 89;

    private static final int MAGIC = 0x41535232; // "ASR2"
    private static final int HEADER_BYTES = Integer.BYTES + 1 + Integer.BYTES + 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    enum Type {
        SERVER_HELLO(1, NONCE_BYTES),
        CLIENT_HELLO(2, NONCE_BYTES * 2),
        AUTH_CHALLENGE(3, SECRET_BYTES + NONCE_BYTES),
        AUTHENTICATE(4, PROOF_BYTES),
        SERVER_CHALLENGE(5, NONCE_BYTES + PROOF_BYTES),
        CONNECTION_CHECK(6, PROOF_BYTES),
        CONNECTION_ACK(7, PROOF_BYTES);

        private final int id;
        private final int bodyLength;

        Type(int id, int bodyLength) {
            this.id = id;
            this.bodyLength = bodyLength;
        }

        static Type fromId(int id) {
            for (Type type : values()) {
                if (type.id == id) {
                    return type;
                }
            }
            return null;
        }
    }

    record Message(Type type, int protocolVersion, UUID sessionId, byte[] body) {
        Message {
            if (type == null || sessionId == null || body == null) {
                throw new IllegalArgumentException("Handshake message fields cannot be null");
            }
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    private CompanionHandshakeProtocol() {
    }

    static byte[] randomBytes(int length) {
        if (length <= 0 || length > NONCE_BYTES) {
            throw new IllegalArgumentException("Invalid random byte count: " + length);
        }
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    static byte[] encodeServerHello(UUID sessionId, byte[] serverNonce) {
        requireLength(serverNonce, NONCE_BYTES, "serverNonce");
        return encode(Type.SERVER_HELLO, sessionId, serverNonce);
    }

    static byte[] encodeClientHello(UUID sessionId, byte[] serverNonce, byte[] clientNonce) {
        requireLength(serverNonce, NONCE_BYTES, "serverNonce");
        requireLength(clientNonce, NONCE_BYTES, "clientNonce");
        return encode(Type.CLIENT_HELLO, sessionId, serverNonce, clientNonce);
    }

    static byte[] encodeAuthChallenge(UUID sessionId, byte[] sessionSecret, byte[] authNonce) {
        requireLength(sessionSecret, SECRET_BYTES, "sessionSecret");
        requireLength(authNonce, NONCE_BYTES, "authNonce");
        return encode(Type.AUTH_CHALLENGE, sessionId, sessionSecret, authNonce);
    }

    static byte[] encodeAuthenticate(UUID sessionId, byte[] proof) {
        requireLength(proof, PROOF_BYTES, "proof");
        return encode(Type.AUTHENTICATE, sessionId, proof);
    }

    static byte[] encodeServerChallenge(UUID sessionId, byte[] checkNonce, byte[] serverProof) {
        requireLength(checkNonce, NONCE_BYTES, "checkNonce");
        requireLength(serverProof, PROOF_BYTES, "serverProof");
        return encode(Type.SERVER_CHALLENGE, sessionId, checkNonce, serverProof);
    }

    static byte[] encodeConnectionCheck(UUID sessionId, byte[] proof) {
        requireLength(proof, PROOF_BYTES, "proof");
        return encode(Type.CONNECTION_CHECK, sessionId, proof);
    }

    static byte[] encodeConnectionAck(UUID sessionId, byte[] proof) {
        requireLength(proof, PROOF_BYTES, "proof");
        return encode(Type.CONNECTION_ACK, sessionId, proof);
    }

    static Message decode(byte[] payload) {
        if (payload == null || payload.length < HEADER_BYTES || payload.length > MAX_MESSAGE_BYTES) {
            return null;
        }

        try {
            ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
            if (buffer.getInt() != MAGIC) {
                return null;
            }

            Type type = Type.fromId(Byte.toUnsignedInt(buffer.get()));
            if (type == null) {
                return null;
            }

            int protocolVersion = buffer.getInt();
            UUID sessionId = new UUID(buffer.getLong(), buffer.getLong());
            if (buffer.remaining() != type.bodyLength) {
                return null;
            }

            byte[] body = new byte[buffer.remaining()];
            buffer.get(body);
            return new Message(type, protocolVersion, sessionId, body);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    static byte[] createClientAuthProof(
        byte[] secret,
        UUID sessionId,
        byte[] serverNonce,
        byte[] clientNonce,
        byte[] authNonce
    ) {
        return createProof("client-auth", secret, sessionId, serverNonce, clientNonce, authNonce);
    }

    static byte[] createServerChallengeProof(
        byte[] secret,
        UUID sessionId,
        byte[] serverNonce,
        byte[] clientNonce,
        byte[] authNonce,
        byte[] checkNonce
    ) {
        return createProof("server-challenge", secret, sessionId, serverNonce, clientNonce, authNonce, checkNonce);
    }

    static byte[] createClientCheckProof(
        byte[] secret,
        UUID sessionId,
        byte[] serverNonce,
        byte[] clientNonce,
        byte[] authNonce,
        byte[] checkNonce
    ) {
        return createProof("client-connection-check", secret, sessionId, serverNonce, clientNonce, authNonce, checkNonce);
    }

    static byte[] createServerAckProof(
        byte[] secret,
        UUID sessionId,
        byte[] serverNonce,
        byte[] clientNonce,
        byte[] authNonce,
        byte[] checkNonce,
        byte[] clientCheckProof
    ) {
        return createProof("server-ack", secret, sessionId, serverNonce, clientNonce, authNonce, checkNonce, clientCheckProof);
    }

    static boolean constantTimeEquals(byte[] expected, byte[] actual) {
        return expected != null && actual != null && MessageDigest.isEqual(expected, actual);
    }

    private static byte[] encode(Type type, UUID sessionId, byte[]... parts) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId cannot be null");
        }

        byte[] body = concatenate(parts);
        if (body.length != type.bodyLength) {
            throw new IllegalArgumentException("Invalid body size for " + type + ": " + body.length);
        }

        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + body.length).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(MAGIC);
        buffer.put((byte) type.id);
        buffer.putInt(PROTOCOL_VERSION);
        buffer.putLong(sessionId.getMostSignificantBits());
        buffer.putLong(sessionId.getLeastSignificantBits());
        buffer.put(body);
        return buffer.array();
    }

    private static byte[] createProof(String purpose, byte[] secret, UUID sessionId, byte[]... values) {
        requireLength(secret, SECRET_BYTES, "secret");
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId cannot be null");
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update("ArenaSystem/Handshake".getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);

            ByteBuffer header = ByteBuffer.allocate(Integer.BYTES + 16).order(ByteOrder.BIG_ENDIAN);
            header.putInt(PROTOCOL_VERSION);
            header.putLong(sessionId.getMostSignificantBits());
            header.putLong(sessionId.getLeastSignificantBits());
            mac.update(header.array());

            byte[] purposeBytes = purpose.getBytes(StandardCharsets.UTF_8);
            updateLengthPrefixed(mac, purposeBytes);
            for (byte[] value : values) {
                requireLength(value, NONCE_BYTES, "proof input");
                updateLengthPrefixed(mac, value);
            }
            return mac.doFinal();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is not available", exception);
        }
    }

    private static void updateLengthPrefixed(Mac mac, byte[] value) {
        mac.update(ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN).putInt(value.length).array());
        mac.update(value);
    }

    private static byte[] concatenate(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            if (part == null) {
                throw new IllegalArgumentException("Handshake body part cannot be null");
            }
            length = Math.addExact(length, part.length);
        }

        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    private static void requireLength(byte[] value, int expected, String name) {
        if (value == null || value.length != expected) {
            throw new IllegalArgumentException(name + " must be exactly " + expected + " bytes");
        }
    }
}
