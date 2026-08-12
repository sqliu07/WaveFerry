package app.shengdu.audio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MainActivityStateTest {
    @Test
    public void enablesSwitchOnlyForDifferentReceiverDuringStreaming() {
        assertTrue(MainActivity.isReceiverSwitchPending(
                StreamingService.STATUS_STREAMING, "airplay:living-room", "dlna:tv"));
        assertFalse(MainActivity.isReceiverSwitchPending(
                StreamingService.STATUS_STREAMING, "dlna:tv", "dlna:tv"));
        assertFalse(MainActivity.isReceiverSwitchPending(
                StreamingService.STATUS_CONNECTING, "airplay:living-room", "dlna:tv"));
        assertFalse(MainActivity.isReceiverSwitchPending(
                StreamingService.STATUS_STREAMING, "", "dlna:tv"));
    }
}
