package id.hadziq.arenasystem;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class TestSkillInputProtocol {
   private TestSkillInputProtocol() {
   }

   public static void main(String[] args) {
      assertAction(new byte[] {0x41, 0x52, 0x53, 0x49, 0x01, 0x01}, SkillInputProtocol.ACTION_PRIMARY);
      assertAction(new byte[] {0x41, 0x52, 0x53, 0x49, 0x01, 0x02}, SkillInputProtocol.ACTION_SECONDARY);

      assertRejected(null);
      assertRejected(new byte[0]);
      assertRejected(new byte[5]);
      assertRejected(new byte[7]);
      assertRejected(new byte[] {0, 0, 0, 0, 1, 1});
      assertRejected(new byte[] {0x41, 0x52, 0x53, 0x49, 0x02, 0x01});
      assertRejected(new byte[] {0x41, 0x52, 0x53, 0x49, 0x01, 0x03});

      System.out.println("ArenaSystem skill input server protocol tests passed.");
   }

   private static void assertAction(byte[] payload, byte expected) {
      byte actual = SkillInputProtocol.decodeAction(payload);
      if (actual != expected) {
         throw new AssertionError("Expected action " + expected + " but received " + actual
               + " for " + Arrays.toString(payload));
      }
   }

   private static void assertRejected(byte[] payload) {
      if (SkillInputProtocol.decodeAction(payload) != 0) {
         throw new AssertionError("Malformed skill input payload was accepted: "
               + Arrays.toString(payload));
      }
   }
}
