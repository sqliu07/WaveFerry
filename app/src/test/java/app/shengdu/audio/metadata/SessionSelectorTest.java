package app.shengdu.audio.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import app.shengdu.audio.metadata.SessionSelector.Descriptor;
import app.shengdu.audio.metadata.SessionSelector.State;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class SessionSelectorTest {
    private static final String OWN = "app.shengdu.audio";
    private static final String APPLE_MUSIC = "com.apple.android.music";

    private static SessionSelector selector() {
        return new SessionSelector(OWN, Set.of(APPLE_MUSIC));
    }

    private static Descriptor playing(String id, String pkg, boolean hasTitle) {
        return new Descriptor(id, pkg, State.PLAYING, hasTitle, hasTitle);
    }

    @Test
    public void ownPackageIsNeverSelected() {
        SessionSelector selector = selector();

        Object selected = selector.update(List.of(
                new Descriptor("self", OWN, State.PLAYING, true, true)));

        assertNull(selected);
    }

    @Test
    public void playingBeatsPausedOrBuffering() {
        SessionSelector selector = selector();

        Object selected = selector.update(List.of(
                new Descriptor("paused", "a", State.PAUSED_OR_BUFFERING, true, true),
                new Descriptor("playing", "b", State.PLAYING, true, true)));

        assertEquals("playing", selected);
    }

    @Test
    public void sessionWithTitleBeatsTitlelessAtSameRank() {
        SessionSelector selector = selector();

        Object selected = selector.update(List.of(
                playing("titleless", "a", false),
                playing("titled", "b", true)));

        assertEquals("titled", selected);
    }

    @Test
    public void stickySelectionSurvivesAnotherPlayingSession() {
        SessionSelector selector = selector();
        selector.update(List.of(playing("first", "a", true)));

        Object selected = selector.update(List.of(
                playing("first", "a", true),
                playing("second", "b", true)));

        assertEquals("first", selected);
    }

    @Test
    public void pausedStickyYieldsToNewPlayingSession() {
        SessionSelector selector = selector();
        selector.update(List.of(playing("first", "a", true)));

        Object selected = selector.update(List.of(
                new Descriptor("first", "a", State.PAUSED_OR_BUFFERING, true, true),
                playing("second", "b", true)));

        assertEquals("second", selected);
    }

    @Test
    public void vanishedStickyReselectsBestRemaining() {
        SessionSelector selector = selector();
        selector.update(List.of(
                playing("gone", "a", true),
                new Descriptor("rest", "b", State.PAUSED_OR_BUFFERING, true, true)));

        Object selected = selector.update(List.of(
                new Descriptor("rest", "b", State.PAUSED_OR_BUFFERING, true, true)));

        assertEquals("rest", selected);
    }

    @Test
    public void stoppedStickyLosesStickinessButStaysSelectable() {
        SessionSelector selector = selector();
        selector.update(List.of(playing("first", "a", true)));

        Object selected = selector.update(List.of(
                new Descriptor("first", "a", State.OTHER, true, true),
                playing("second", "b", true)));

        // OTHER-ranked sessions are not kept by stickiness; the PLAYING challenger wins.
        assertEquals("second", selected);
    }

    @Test
    public void bonusPackageBreaksExactTies() {
        SessionSelector selector = selector();

        Object selected = selector.update(List.of(
                playing("other", "com.other.player", true),
                playing("apple", APPLE_MUSIC, true)));

        assertEquals("apple", selected);
    }

    @Test
    public void opaqueSessionTokenIsReturnedWithoutConvertingToPackageName() {
        SessionSelector selector = selector();
        Object token = new Object();

        Object selected = selector.update(List.of(
                new Descriptor(token, "com.example.player", State.PLAYING, true, true)));

        assertSame(token, selected);
    }

    @Test
    public void emptySessionListSelectsNothing() {
        SessionSelector selector = selector();
        selector.update(List.of(playing("gone", "a", true)));

        assertNull(selector.update(Collections.emptyList()));
    }
}
