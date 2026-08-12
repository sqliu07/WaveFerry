package app.shengdu.audio.dlna;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DlnaStreamingClientTest {
    @Test
    public void mapsRendererPlaybackChangesToPhoneCommands() {
        assertEquals(
                DlnaStreamingClient.RemoteCommand.PAUSE,
                DlnaStreamingClient.remoteCommand("PLAYING", "PAUSED_PLAYBACK"));
        assertEquals(
                DlnaStreamingClient.RemoteCommand.PLAY,
                DlnaStreamingClient.remoteCommand("PAUSED_PLAYBACK", "PLAYING"));
        assertEquals(
                DlnaStreamingClient.RemoteCommand.PAUSE,
                DlnaStreamingClient.remoteCommand("PLAYING", "STOPPED"));
    }

    @Test
    public void ignoresDuplicateAndIntermediateRendererStates() {
        assertEquals(
                DlnaStreamingClient.RemoteCommand.NONE,
                DlnaStreamingClient.remoteCommand("PLAYING", "playing"));
        assertEquals(
                DlnaStreamingClient.RemoteCommand.NONE,
                DlnaStreamingClient.remoteCommand("PLAYING", "TRANSITIONING"));
        assertEquals(
                DlnaStreamingClient.RemoteCommand.NONE,
                DlnaStreamingClient.remoteCommand("PLAYING", ""));
    }
}
