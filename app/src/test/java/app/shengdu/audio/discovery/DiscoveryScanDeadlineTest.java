package app.shengdu.audio.discovery;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DiscoveryScanDeadlineTest {
    @Test
    public void emptyScanExpiresOnlyOnce() {
        DiscoveryScanDeadline deadline = new DiscoveryScanDeadline();

        deadline.begin(false);

        assertTrue(deadline.isArmed());
        assertTrue(deadline.expire());
        assertTrue(deadline.hasTimedOut());
        assertFalse(deadline.expire());
    }

    @Test
    public void findingADeviceCancelsTheDeadline() {
        DiscoveryScanDeadline deadline = new DiscoveryScanDeadline();

        deadline.begin(false);
        deadline.onDevicesFound();

        assertFalse(deadline.isArmed());
        assertFalse(deadline.hasTimedOut());
        assertFalse(deadline.expire());
    }

    @Test
    public void existingDevicesDoNotArmAnEmptyScanDeadline() {
        DiscoveryScanDeadline deadline = new DiscoveryScanDeadline();

        deadline.begin(true);

        assertFalse(deadline.isArmed());
        assertFalse(deadline.expire());
    }

    @Test
    public void aNewScanClearsThePreviousTimeoutState() {
        DiscoveryScanDeadline deadline = new DiscoveryScanDeadline();
        deadline.begin(false);
        deadline.expire();

        deadline.begin(false);

        assertTrue(deadline.isArmed());
        assertFalse(deadline.hasTimedOut());
    }
}
