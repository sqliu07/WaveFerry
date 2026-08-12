package app.shengdu.audio.dlna;

import static org.junit.Assert.assertTrue;

import java.net.URI;

import org.junit.Test;

public final class DlnaLiveAudioMetadataTest {
    @Test
    public void describesTheLiveWavEndpointAsDlnaAudio() throws Exception {
        String didl = DlnaLiveAudioMetadata.didlLite(
                new URI("http://192.168.1.2/waveferry/live.wav?x=1&y=2"));

        assertTrue(didl.contains("DIDL-Lite"));
        assertTrue(didl.contains("object.item.audioItem.musicTrack"));
        assertTrue(didl.contains("audio/wav"));
        assertTrue(didl.contains("x=1&amp;y=2"));
    }

    @Test
    public void includesEscapedTrackDetailsAndArtwork() throws Exception {
        URI mediaUri = new URI("http://192.168.1.2/waveferry/live.wav");
        URI artworkUri = new URI("http://192.168.1.2/waveferry/art.jpg?v=1&size=small");

        String didl = DlnaLiveAudioMetadata.didlLite(
                mediaUri, "A <Song>", "B & C", "Album 'One'", artworkUri, 245_678L);

        assertTrue(didl.contains("<dc:title>A &lt;Song&gt;</dc:title>"));
        assertTrue(didl.contains("<dc:creator>B &amp; C</dc:creator>"));
        assertTrue(didl.contains("<upnp:artist>B &amp; C</upnp:artist>"));
        assertTrue(didl.contains("<upnp:album>Album &apos;One&apos;</upnp:album>"));
        assertTrue(didl.contains("dlna:profileID=\"JPEG_SM\""));
        assertTrue(didl.contains("art.jpg?v=1&amp;size=small"));
        assertTrue(didl.contains("duration=\"0:04:05.678\""));
    }
}
