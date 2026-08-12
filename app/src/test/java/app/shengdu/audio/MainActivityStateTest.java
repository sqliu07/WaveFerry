package app.shengdu.audio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import app.shengdu.audio.discovery.ReceiverDevice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @Test
    public void keepsConnectedReceiverWhenDiscoveryRoundDoesNotReturnIt() {
        ReceiverDevice active = receiver("dlna:tv", "Living Room TV");
        List<ReceiverDevice> available = new ArrayList<>();

        ReceiverDevice retained = MainActivity.retainActiveReceiver(
                available, active.getStableKey(), active);

        assertSame(active, retained);
        assertEquals(List.of(active), available);
    }

    @Test
    public void replacesPinnedReceiverWithFreshDiscoveryData() {
        ReceiverDevice active = receiver("dlna:tv", "Old name");
        ReceiverDevice refreshed = receiver("dlna:tv", "Living Room TV");
        List<ReceiverDevice> available = new ArrayList<>(List.of(refreshed));

        ReceiverDevice retained = MainActivity.retainActiveReceiver(
                available, active.getStableKey(), active);

        assertSame(refreshed, retained);
        assertEquals(1, available.size());
    }

    private static ReceiverDevice receiver(String key, String name) {
        String type = "urn:schemas-upnp-org:device:MediaRenderer:1";
        return new ReceiverDevice(
                key,
                name,
                type,
                key,
                "192.168.1.8",
                49152,
                Map.of("transport", "dlna"),
                Set.of(type));
    }
}
