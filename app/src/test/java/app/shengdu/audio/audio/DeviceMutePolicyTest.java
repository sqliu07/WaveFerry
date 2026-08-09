package app.shengdu.audio.audio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DeviceMutePolicyTest {
    @Test
    public void mutesWhenEnabledAndStreamIsAudible() {
        DeviceMutePolicy policy = new DeviceMutePolicy();

        assertEquals(0, policy.onStreamingStarted(true, 7));
    }

    @Test
    public void doesNotMuteWhenDisabled() {
        DeviceMutePolicy policy = new DeviceMutePolicy();

        assertEquals(DeviceMutePolicy.NO_CHANGE, policy.onStreamingStarted(false, 7));
        assertEquals(DeviceMutePolicy.NO_CHANGE, policy.onStreamingStopped(0));
    }

    @Test
    public void doesNotMuteAnAlreadySilentStream() {
        DeviceMutePolicy policy = new DeviceMutePolicy();

        assertEquals(DeviceMutePolicy.NO_CHANGE, policy.onStreamingStarted(true, 0));
        // Nothing was applied, so nothing may be restored afterwards.
        assertEquals(DeviceMutePolicy.NO_CHANGE, policy.onStreamingStopped(0));
    }

    @Test
    public void restoresSavedVolumeWhenStreamIsStillMuted() {
        DeviceMutePolicy policy = new DeviceMutePolicy();
        policy.onStreamingStarted(true, 7);

        assertEquals(7, policy.onStreamingStopped(0));
    }

    @Test
    public void keepsUserVolumeChosenDuringCasting() {
        DeviceMutePolicy policy = new DeviceMutePolicy();
        policy.onStreamingStarted(true, 7);

        // The user turned the volume up to 5 while casting: do not override it.
        assertEquals(DeviceMutePolicy.NO_CHANGE, policy.onStreamingStopped(5));
    }

    @Test
    public void restoresOnlyOncePerSession() {
        DeviceMutePolicy policy = new DeviceMutePolicy();
        policy.onStreamingStarted(true, 7);

        assertEquals(7, policy.onStreamingStopped(0));
        assertEquals(DeviceMutePolicy.NO_CHANGE, policy.onStreamingStopped(0));
    }
}
