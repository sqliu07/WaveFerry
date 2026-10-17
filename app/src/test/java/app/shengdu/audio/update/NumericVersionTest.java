package app.shengdu.audio.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class NumericVersionTest {
    @Test
    public void comparesEveryComponentNumerically() {
        assertTrue(compare("v0.2.10", "0.2.2") > 0);
        assertTrue(compare("0.10.0", "0.9.99") > 0);
        assertTrue(compare("1.0.0", "0.99.99") > 0);
        assertTrue(compare("0.2.1", "0.2.2") < 0);
    }

    @Test
    public void prefixAndTrailingZeroComponentsDoNotChangeVersion() {
        assertEquals(0, compare("v0.2.2", "0.2.2"));
        assertEquals(0, compare("V1.2", "1.2.0"));
        assertEquals(0, compare("1.2.00", "1.02"));
        assertEquals("0.2.3", NumericVersion.parse("v0.2.3").getName());
    }

    @Test
    public void comparesLargeComponentsWithoutIntegerOverflow() {
        assertTrue(compare("1.999999999999999999999999", "1.2147483647") > 0);
        assertTrue(compare("1.1000000000000000000000000", "1.999999999999999999999999") > 0);
    }

    @Test
    public void rejectsInvalidAndPrereleaseVersions() {
        String[] invalid = {null, "", "v", "v0.2.3-beta", "0.2.3+test", " 0.2.3",
                "0.2.3 ", ".2.3", "0..3", "0.2.", "-1.2.3", "0.a.3", "v0/2/3",
                "1\n", "１.２.３", "v" + "1".repeat(128)};
        for (String value : invalid) {
            try {
                NumericVersion.parse(value);
                fail("Accepted invalid version: " + value);
            } catch (IllegalArgumentException expected) {
                // Expected.
            }
        }
    }

    private static int compare(String left, String right) {
        return NumericVersion.parse(left).compareTo(NumericVersion.parse(right));
    }
}
