package id.hadziq.arenasystem;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/**
 * Wire contract shared with the Fabric companion client:
 * server challenge = UUID (16 bytes);
 * client response = protocol version (4 bytes) + same UUID (16 bytes).
 */
final class CompanionHandshakeProtocol {
    static final int PROTOCOL_VERSION = 1;
    static final int CHALLENGE_BYTES = 16;
    static final int RESPONSE_BYTES = 20;

    private CompanionHandshakeProtocol() {
    }

    static byte[] encodeChallenge(UUID nonce) {
        if (nonce == null) throw new IllegalArgumentException("nonce cannot be null");
        return ByteBuffer.allocate(CHALLENGE_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(nonce.getMostSignificantBits())
                .putLong(nonce.getLeastSignificantBits())
                .array();
    }

    static boolean isValidResponse(byte[] payload, UUID expectedNonce) {
        if (payload == null || expectedNonce == null || payload.length != RESPONSE_BYTES) {
            return false;
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int version = buffer.getInt();
        UUID receivedNonce = new UUID(buffer.getLong(), buffer.getLong());
        return version == PROTOCOL_VERSION && expectedNonce.equals(receivedNonce);
    }
}
