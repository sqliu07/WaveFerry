package app.shengdu.audio.discovery;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public final class ReceiverDeviceSerializationTest {
    @Test
    public void roundTripsActiveReceiverSnapshot() throws Exception {
        ReceiverDevice source = new ReceiverDevice(
                "dlna:uuid:tv",
                "Living Room TV",
                ReceiverDiscoverySupport.SERVICE_TYPE_DLNA,
                "uuid:tv",
                "192.168.1.8",
                49152,
                Map.of("transport", "dlna"),
                Set.of(ReceiverDiscoverySupport.SERVICE_TYPE_DLNA));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(source);
        }
        ReceiverDevice restored;
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (ReceiverDevice) input.readObject();
        }

        assertEquals(source, restored);
    }
}
