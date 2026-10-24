package io.github.muntashirakon.crypto.spake2;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
public class Spake25519Test {
    @Test
    public void spake2() {
        for (int i = 0; i < 20; i++) {
            SPAKE2Run spake2 = new SPAKE2Run();
            assertTrue(spake2.run());
            assertTrue(spake2.keyMatches());
        }
    }

    @Test
    public void oldAlice() {
        for (int i = 0; i < 20; i++) {
            SPAKE2Run spake2 = new SPAKE2Run();
            spake2.aliceDisablePasswordScalarHack = true;
            assertTrue(spake2.run());
            assertTrue(spake2.keyMatches());
        }
    }

    @Test
    public void oldBob() {
        for (int i = 0; i < 20; i++) {
            SPAKE2Run spake2 = new SPAKE2Run();
            spake2.bobDisablePasswordScalarHack = true;
            assertTrue(spake2.run());
            assertTrue(spake2.keyMatches());
        }
    }

    @Test
    public void wrongPassword() {
        SPAKE2Run spake2 = new SPAKE2Run();
        spake2.bobPassword = "wrong password".getBytes(StandardCharsets.UTF_8);
        assertTrue(spake2.run());
        assertFalse(spake2.keyMatches());
    }

    @Test
    public void wrongNames() {
        SPAKE2Run spake2 = new SPAKE2Run();
        spake2.aliceNames.second = "charlie";
        spake2.bobNames.second = "charlie";
        assertTrue(spake2.run());
        assertFalse(spake2.keyMatches());
    }

    @Test
    public void corruptMessages() {
        for (int i = 0; i < 8 * Spake2Context.MAX_MSG_SIZE; i++) {
            SPAKE2Run spake2 = new SPAKE2Run();
            spake2.aliceCorruptMsgBit = i;
            assertFalse(spake2.run() && spake2.keyMatches());
        }
    }

    // Based on https://android.googlesource.com/platform/external/boringssl/+/f9e0b0e17fabac35627f18f94a8954c3857784ac/src/crypto/curve25519/spake25519_test.cc
    static byte[] hex(String text) {
        byte[] out = new byte[text.length()/2];
        for (int i = 0; i < out.length; i++) out[i] = (byte)Integer.parseInt(text.substring(i*2,i*2+2),16);
        return out;
    }
    @Test public void destructionIsIdempotent() {
        Spake2Context c = new Spake2Context(Spake2Role.Alice, new byte[]{1}, new byte[]{2});
        c.destroy(); c.destroy(); assertTrue(c.isDestroyed());
        assertThrows(IllegalStateException.class, () -> c.generateMessage(new byte[]{1}));
    }
    @Test public void randomExchanges() {
        byte[] a = "adb pair client\0".getBytes(StandardCharsets.UTF_8), b = "adb pair server\0".getBytes(StandardCharsets.UTF_8);
        byte[] prior = null;
        for (int i = 0; i < 25; i++) {
            Spake2Context alice = new Spake2Context(Spake2Role.Alice,a,b), bob = new Spake2Context(Spake2Role.Bob,b,a);
            try {
                byte[] am = alice.generateMessage(new byte[]{1,2,3}), bm = bob.generateMessage(new byte[]{1,2,3});
                assertArrayEquals(alice.processMessage(bm), bob.processMessage(am));
                if (prior != null) assertFalse(Arrays.equals(prior,am));
                prior = am;
            } finally { alice.destroy(); bob.destroy(); }
        }
    }
    private static class SPAKE2Run {
        private final Pair<String, String> aliceNames = new Pair<>("adb pair client\u0000", "adb pair server\u0000");
        private final Pair<String, String> bobNames = new Pair<>("adb pair server\u0000", "adb pair client\u0000");
        private final byte[] alicePassword = hex("353932373831E63DD959651C211600F3B6561D0B9D90AF09D0A4A453EE2059A480CC7C5A94D4D48933F9FFF5FE43317D52FA7BFF8F8BC4F3488B8007330FEC7C7EDC91C20E5D");
        private byte[] bobPassword = alicePassword;
        private boolean aliceDisablePasswordScalarHack = false;
        private boolean bobDisablePasswordScalarHack = false;
        private int aliceCorruptMsgBit = -1;
        private boolean keyMatches = false;

        private boolean run() {
            Spake2Context alice = new Spake2Context(
                    Spake2Role.Alice,
                    aliceNames.first.getBytes(StandardCharsets.UTF_8),
                    aliceNames.second.getBytes(StandardCharsets.UTF_8));
            Spake2Context bob = new Spake2Context(
                    Spake2Role.Bob,
                    bobNames.first.getBytes(StandardCharsets.UTF_8),
                    bobNames.second.getBytes(StandardCharsets.UTF_8));

            if (aliceDisablePasswordScalarHack) {
                alice.setDisablePasswordScalarHack(true);
            }
            if (bobDisablePasswordScalarHack) {
                bob.setDisablePasswordScalarHack(true);
            }

            byte[] aliceMsg;
            byte[] bobMsg;

            try {
                aliceMsg = alice.generateMessage(alicePassword, hex("47f6c458e5f062db8427d2d9bb20c954a76d6943959756a18d11d45e1ad190f980a86d185a93ca1d3025c5febe3aac4045b34a39b1f511385ca97fc4332137f3"));
                bobMsg = bob.generateMessage(bobPassword, hex("a6bf9f9bf7819e0ded8c2dd82a1aa38acb2f8a6403429cff33d64ea9c40439d5fd7029811a5f5a8f7c89c8b44ac0b421f6b24ca2ba18d2069995831730cd8c5a"));
            } catch (Exception e) {
                alice.destroy(); bob.destroy();
                return false;
            }


            if (aliceCorruptMsgBit >= 0 && aliceCorruptMsgBit < (8 * aliceMsg.length)) {
                aliceMsg[aliceCorruptMsgBit / 8] ^= 1 << (aliceCorruptMsgBit & 7);
            }

            byte[] aliceKey;
            byte[] bobKey;
            try {
                aliceKey = alice.processMessage(bobMsg);
                bobKey = bob.processMessage(aliceMsg);
            } catch (Exception e) {
                alice.destroy(); bob.destroy();
                return false;
            }


            keyMatches = Arrays.equals(aliceKey, bobKey);

            alice.destroy(); bob.destroy();
            return true;
        }

        boolean keyMatches() {
            return keyMatches;
        }
    }

    private static class Pair<S, T> {
        private S first;
        private T second;

        public Pair(S first, T second) {
            this.first = first;
            this.second = second;
        }
    }
}
