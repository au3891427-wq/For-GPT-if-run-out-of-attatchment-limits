package id.hadziq.arenasystem;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class SkillInputProtocol {
   static final String CHANNEL = "arenasystem:skill_input";
   static final byte ACTION_PRIMARY = 1;
   static final byte ACTION_SECONDARY = 2;
   private static final int MESSAGE_BYTES = 6;
   private static final int MAGIC = 1095914313;
   private static final byte VERSION = 1;

   private SkillInputProtocol() {
   }

   static byte decodeAction(byte[] payload) {
      if (payload != null && payload.length == 6) {
         ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
         if (buffer.getInt() == 1095914313 && buffer.get() == 1) {
            byte action = buffer.get();
            return action != 1 && action != 2 ? 0 : action;
         } else {
            return 0;
         }
      } else {
         return 0;
      }
   }
}
