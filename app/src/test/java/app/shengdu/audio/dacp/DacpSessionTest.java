package app.shengdu.audio.dacp;

import static org.junit.Assert.assertEquals;

import java.security.SecureRandom;
import org.junit.Test;

public final class DacpSessionTest {
    @Test
    public void generatedDacpIdIsEightUppercaseHexBytes() {
        SecureRandom random = new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                for (int index = 0; index < bytes.length; index++) {
                    bytes[index] = (byte) (0xa0 + index);
                }
            }
        };

        assertEquals("A0A1A2A3A4A5A6A7", DacpSession.randomHexId(random));
    }

    @Test
    public void generatedActiveRemoteIsNonZeroUnsignedDecimal() {
        SecureRandom random = new SecureRandom() {
            private int calls;

            @Override
            public int nextInt() {
                return calls++ == 0 ? 0 : -1;
            }
        };

        assertEquals("4294967295", DacpSession.randomActiveRemote(random));
    }
}
