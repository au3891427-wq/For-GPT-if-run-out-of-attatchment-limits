package id.hadziq.arenasystem;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Strict decoder for the versioned Fabric client class-skill input payload.
 */
final class SkillInputProtocol {
   static final String CHANNEL = "arenasystem:skill_input";
   static final byte ACTION_PRIMARY = 1;
   static final byte ACTION_SECONDARY = 2;

   private static final int MESSAGE_BYTES = 6;
   private static final int MAGIC = 0x41525349; // "ARSI"
   private static final byte VERSION = 1;

   private SkillInputProtocol() {
   }

   static byte decodeAction(byte[] payload) {
      if (payload == null || payload.length != MESSAGE_BYTES) {
         return 0;
      }

      ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
      if (buffer.getInt() != MAGIC || buffer.get() != VERSION) {
         return 0;
      }

      byte action = buffer.get();
      return action == ACTION_PRIMARY || action == ACTION_SECONDARY ? action : 0;
   }
}
