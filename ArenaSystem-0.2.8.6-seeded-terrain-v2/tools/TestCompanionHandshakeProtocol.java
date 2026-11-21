package id.hadziq.arenasystem;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/** Standalone protocol smoke tests; run with assertions enabled. */
public final class TestCompanionHandshakeProtocol {
    public static void main(String[] args) {
        UUID nonce = UUID.fromString("12345678-9abc-def0-1234-56789abcdef0");

        byte[] challenge = CompanionHandshakeProtocol.encodeChallenge(nonce);
        assert challenge.length == CompanionHandshakeProtocol.CHALLENGE_BYTES;
        ByteBuffer challengeBuffer = ByteBuffer.wrap(challenge).order(ByteOrder.BIG_ENDIAN);
        assert challengeBuffer.getLong() == nonce.getMostSignificantBits();
        assert challengeBuffer.getLong() == nonce.getLeastSignificantBits();

        byte[] response = ByteBuffer.allocate(CompanionHandshakeProtocol.RESPONSE_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(CompanionHandshakeProtocol.PROTOCOL_VERSION)
                .putLong(nonce.getMostSignificantBits())
                .putLong(nonce.getLeastSignificantBits())
                .array();
        assert CompanionHandshakeProtocol.isValidResponse(response, nonce);
        assert !CompanionHandshakeProtocol.isValidResponse(response, UUID.randomUUID());

        byte[] wrongVersion = response.clone();
        ByteBuffer.wrap(wrongVersion).order(ByteOrder.BIG_ENDIAN).putInt(2);
        assert !CompanionHandshakeProtocol.isValidResponse(wrongVersion, nonce);

        assert !CompanionHandshakeProtocol.isValidResponse(new byte[19], nonce);
        assert !CompanionHandshakeProtocol.isValidResponse(new byte[21], nonce);
        assert !CompanionHandshakeProtocol.isValidResponse(null, nonce);
        assert !CompanionHandshakeProtocol.isValidResponse(response, null);

        System.out.println("Companion handshake protocol tests passed.");
    }
}
