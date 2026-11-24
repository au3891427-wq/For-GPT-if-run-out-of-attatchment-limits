package id.hadziq.arenasystem;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class TestSkillInputProtocol {
    private static final int MAGIC = 0x41525349;
    private static final byte VERSION = 1;

    private TestSkillInputProtocol() {
    }

    public static void main(String[] args) {
        assertAction("primary", SkillInputProtocol.ACTION_PRIMARY);
        assertAction("secondary", SkillInputProtocol.ACTION_SECONDARY);
        assertAction("Assassin off-hand", SkillInputProtocol.ACTION_ASSASSIN_OFFHAND);
        assertAction("Sniper Aim Lock", SkillInputProtocol.ACTION_SNIPER_AIM_LOCK);

        assertInvalid(null);
        assertInvalid(new byte[5]);
        assertInvalid(new byte[7]);
        assertInvalid(message(0x11111111, VERSION, SkillInputProtocol.ACTION_ASSASSIN_OFFHAND));
        assertInvalid(message(MAGIC, (byte) 2, SkillInputProtocol.ACTION_ASSASSIN_OFFHAND));
        assertInvalid(message(MAGIC, VERSION, (byte) 0));
        assertInvalid(message(MAGIC, VERSION, (byte) 5));

        System.out.println("ArenaSystem server skill input protocol tests passed.");
    }

    private static void assertAction(String name, byte action) {
        byte[] payload = message(MAGIC, VERSION, action);
        byte decoded = SkillInputProtocol.decodeAction(payload);
        if (decoded != action) {
            throw new AssertionError(name + " action mismatch: expected " + action + ", got " + decoded);
        }
    }

    private static void assertInvalid(byte[] payload) {
        if (SkillInputProtocol.decodeAction(payload) != 0) {
            throw new AssertionError("Invalid skill input payload was accepted.");
        }
    }

    private static byte[] message(int magic, byte version, byte action) {
        return ByteBuffer.allocate(6)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(magic)
                .put(version)
                .put(action)
                .array();
    }
}
