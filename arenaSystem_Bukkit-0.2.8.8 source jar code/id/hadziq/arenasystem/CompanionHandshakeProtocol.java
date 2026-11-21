package id.hadziq.arenasystem;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

final class CompanionHandshakeProtocol {
   static final int PROTOCOL_VERSION = 1;
   static final int CHALLENGE_BYTES = 16;
   static final int RESPONSE_BYTES = 20;

   private CompanionHandshakeProtocol() {
   }

   static byte[] encodeChallenge(UUID nonce) {
      if (nonce == null) {
         throw new IllegalArgumentException("nonce cannot be null");
      } else {
         return ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).putLong(nonce.getMostSignificantBits()).putLong(nonce.getLeastSignificantBits()).array();
      }
   }

   static boolean isValidResponse(byte[] payload, UUID expectedNonce) {
      if (payload != null && expectedNonce != null && payload.length == 20) {
         ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
         int version = buffer.getInt();
         UUID receivedNonce = new UUID(buffer.getLong(), buffer.getLong());
         return version == 1 && expectedNonce.equals(receivedNonce);
      } else {
         return false;
      }
   }
}
