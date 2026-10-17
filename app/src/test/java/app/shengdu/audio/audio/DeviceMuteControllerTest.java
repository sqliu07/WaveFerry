package app.shengdu.audio.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class DeviceMuteControllerTest {
    @Test
    public void zeroVolumeIsVerifiedAndRestoredOnlyOnce() {
        FakeVolume volume = new FakeVolume(7);
        DeviceMuteController controller = new DeviceMuteController();

        assertTrue(controller.mute(volume));
        assertEquals(0, volume.index);
        controller.restore(volume);
        controller.restore(volume);

        assertEquals(7, volume.index);
        assertEquals(List.of("volume:0", "volume:7"), volume.writes);
    }

    @Test
    public void ignoredZeroFallsBackToExplicitMuteAndOnlyUnmutesOnRestore() {
        FakeVolume volume = new FakeVolume(7);
        volume.ignoreZero = true;
        DeviceMuteController controller = new DeviceMuteController();

        assertTrue(controller.mute(volume));
        assertEquals(7, volume.index);
        assertTrue(volume.muted);
        controller.restore(volume);
        controller.restore(volume);

        assertFalse(volume.muted);
        assertEquals(7, volume.index);
        assertEquals(List.of("volume:0", "muted:true", "muted:false"), volume.writes);
    }

    @Test
    public void explicitMuteCanReportZeroWithoutChangingUnderlyingIndex() {
        FakeVolume volume = new FakeVolume(7);
        volume.ignoreZero = true;
        volume.hideIndexWhenMuted = true;
        DeviceMuteController controller = new DeviceMuteController();

        assertTrue(controller.mute(volume));
        assertEquals(0, volume.getVolume());
        controller.restore(volume);

        assertEquals(7, volume.index);
        assertEquals(List.of("volume:0", "muted:true", "muted:false"), volume.writes);
    }

    @Test
    public void rejectedZeroCanStillUseExplicitMute() {
        FakeVolume volume = new FakeVolume(7);
        volume.rejectZero = true;
        DeviceMuteController controller = new DeviceMuteController();

        assertTrue(controller.mute(volume));
        assertTrue(volume.muted);
        controller.restore(volume);

        assertFalse(volume.muted);
        assertEquals(7, volume.index);
    }

    @Test
    public void rejectedStrategiesDoNotCreateARestorationObligation() {
        FakeVolume volume = new FakeVolume(7);
        volume.rejectZero = true;
        volume.rejectMute = true;
        DeviceMuteController controller = new DeviceMuteController();

        assertFalse(controller.mute(volume));
        controller.restore(volume);

        assertEquals(7, volume.index);
        assertEquals(List.of("volume:0", "muted:true"), volume.writes);
    }

    @Test
    public void ignoredStrategiesDoNotCreateARestorationObligation() {
        FakeVolume volume = new FakeVolume(7);
        volume.ignoreZero = true;
        volume.ignoreMute = true;
        DeviceMuteController controller = new DeviceMuteController();

        assertFalse(controller.mute(volume));
        controller.restore(volume);

        assertEquals(List.of("volume:0", "muted:true"), volume.writes);
    }

    @Test
    public void fixedVolumeIsNotForced() {
        FakeVolume volume = new FakeVolume(7);
        volume.fixed = true;
        DeviceMuteController controller = new DeviceMuteController();

        assertFalse(controller.mute(volume));
        controller.restore(volume);

        assertTrue(volume.writes.isEmpty());
    }

    @Test
    public void anAlreadyZeroStreamIsNotOwned() {
        FakeVolume volume = new FakeVolume(0);
        DeviceMuteController controller = new DeviceMuteController();

        assertTrue(controller.mute(volume));
        controller.restore(volume);

        assertTrue(volume.writes.isEmpty());
    }

    @Test
    public void anAlreadyMutedStreamIsNotUnmuted() {
        FakeVolume volume = new FakeVolume(7);
        volume.muted = true;
        DeviceMuteController controller = new DeviceMuteController();

        assertTrue(controller.mute(volume));
        controller.restore(volume);

        assertTrue(volume.muted);
        assertTrue(volume.writes.isEmpty());
    }

    @Test
    public void userVolumeChangeWinsOverSavedVolume() {
        FakeVolume volume = new FakeVolume(7);
        DeviceMuteController controller = new DeviceMuteController();

        assertTrue(controller.mute(volume));
        volume.index = 4;
        controller.restore(volume);

        assertEquals(4, volume.index);
        assertEquals(List.of("volume:0"), volume.writes);
    }

    @Test
    public void userUnmuteWinsEvenWhenReportedVolumeRemainsZero() {
        FakeVolume volume = new FakeVolume(7);
        volume.ignoreZero = true;
        DeviceMuteController controller = new DeviceMuteController();

        assertTrue(controller.mute(volume));
        volume.muted = false;
        volume.index = 0;
        controller.restore(volume);

        assertEquals(0, volume.index);
        assertEquals(List.of("volume:0", "muted:true"), volume.writes);
    }

    @Test
    public void explicitUnmuteKeepsTheUsersNewUnderlyingVolume() {
        FakeVolume volume = new FakeVolume(7);
        volume.ignoreZero = true;
        DeviceMuteController controller = new DeviceMuteController();

        assertTrue(controller.mute(volume));
        volume.index = 4;
        controller.restore(volume);

        assertFalse(volume.muted);
        assertEquals(4, volume.index);
    }

    @Test
    public void repeatedMuteDoesNotOverwriteOriginalVolume() {
        FakeVolume volume = new FakeVolume(7);
        DeviceMuteController controller = new DeviceMuteController();

        assertTrue(controller.mute(volume));
        assertTrue(controller.mute(volume));
        controller.restore(volume);

        assertEquals(7, volume.index);
        assertEquals(List.of("volume:0", "volume:7"), volume.writes);
    }

    @Test
    public void repeatedMuteDoesNotOverrideTheUsersVolumeChange() {
        FakeVolume volume = new FakeVolume(7);
        DeviceMuteController controller = new DeviceMuteController();

        assertTrue(controller.mute(volume));
        volume.index = 4;
        assertFalse(controller.mute(volume));
        controller.restore(volume);

        assertEquals(4, volume.index);
        assertEquals(List.of("volume:0"), volume.writes);
    }

    @Test
    public void initialGetterFailureMakesNoChanges() {
        FakeVolume volume = new FakeVolume(7);
        volume.failVolumeReads = true;
        DeviceMuteController controller = new DeviceMuteController();

        assertFalse(controller.mute(volume));
        controller.restore(volume);

        assertTrue(volume.writes.isEmpty());
    }

    @Test
    public void readbackFailuresAfterMutationRetainCleanupState() {
        FakeVolume volume = new FakeVolume(7);
        volume.failReadsAfterWrite = true;
        DeviceMuteController controller = new DeviceMuteController();

        assertFalse(controller.mute(volume));
        assertEquals(0, volume.index);
        assertTrue(volume.muted);
        volume.failReadsAfterWrite = false;
        controller.restore(volume);

        assertEquals(7, volume.index);
        assertFalse(volume.muted);
    }

    @Test
    public void transientRestoreGetterFailureCanBeCleanedUpLater() {
        FakeVolume volume = new FakeVolume(7);
        DeviceMuteController controller = new DeviceMuteController();
        assertTrue(controller.mute(volume));

        volume.failVolumeReads = true;
        controller.restore(volume);
        assertEquals(0, volume.index);
        volume.failVolumeReads = false;
        controller.restore(volume);
        controller.restore(volume);

        assertEquals(7, volume.index);
        assertEquals(List.of("volume:0", "volume:7"), volume.writes);
    }

    @Test
    public void oneRestoreCallRetriesATransientVolumeReadFailure() {
        FakeVolume volume = new FakeVolume(7);
        DeviceMuteController controller = new DeviceMuteController();
        assertTrue(controller.mute(volume));
        volume.volumeReadFailures = 1;

        controller.restore(volume);

        assertEquals(7, volume.index);
        assertEquals(List.of("volume:0", "volume:7"), volume.writes);
    }

    @Test
    public void persistentRestorationFailureOnlyMakesTwoAttempts() {
        FakeVolume volume = new FakeVolume(7);
        DeviceMuteController controller = new DeviceMuteController();
        assertTrue(controller.mute(volume));
        volume.rejectRestore = true;

        controller.restore(volume);

        assertEquals(0, volume.index);
        assertEquals(List.of("volume:0", "volume:7", "volume:7"), volume.writes);
    }

    @Test
    public void failedRestorationDoesNotLoseSavedVolume() {
        FakeVolume volume = new FakeVolume(7);
        DeviceMuteController controller = new DeviceMuteController();
        assertTrue(controller.mute(volume));

        volume.rejectRestore = true;
        controller.restore(volume);
        assertEquals(0, volume.index);
        volume.rejectRestore = false;
        controller.restore(volume);
        controller.restore(volume);

        assertEquals(7, volume.index);
    }

    @Test
    public void aSetterThatChangesVolumeThenThrowsCanStillBeRestored() {
        FakeVolume volume = new FakeVolume(7);
        volume.throwAfterZero = true;
        DeviceMuteController controller = new DeviceMuteController();

        assertTrue(controller.mute(volume));
        controller.restore(volume);

        assertEquals(7, volume.index);
        assertEquals(List.of("volume:0", "volume:7"), volume.writes);
    }

    @Test
    public void oneRestoreCallCompletesAfterAnUnmuteReadbackFailure() {
        FakeVolume volume = new FakeVolume(7);
        volume.zeroAlsoMutes = true;
        volume.failMuteReadAfterUnmute = true;
        DeviceMuteController controller = new DeviceMuteController();
        assertTrue(controller.mute(volume));

        controller.restore(volume);
        assertFalse(volume.muted);
        assertEquals(7, volume.index);
        controller.restore(volume);

        assertEquals(7, volume.index);
        assertEquals(List.of("volume:0", "muted:false", "volume:7"), volume.writes);
    }

    private static final class FakeVolume implements DeviceMuteController.VolumeControl {
        int index;
        boolean muted;
        boolean fixed;
        boolean ignoreZero;
        boolean ignoreMute;
        boolean rejectZero;
        boolean rejectMute;
        boolean rejectRestore;
        boolean throwAfterZero;
        boolean hideIndexWhenMuted;
        boolean failVolumeReads;
        boolean failReadsAfterWrite;
        boolean zeroAlsoMutes;
        boolean failMuteReadAfterUnmute;
        boolean failNextMuteRead;
        int volumeReadFailures;
        final List<String> writes = new ArrayList<>();

        FakeVolume(int index) {
            this.index = index;
        }

        @Override
        public int getVolume() {
            if (volumeReadFailures > 0) {
                volumeReadFailures--;
                throw new SecurityException("Transient volume read failure");
            }
            if (failVolumeReads || (failReadsAfterWrite && !writes.isEmpty())) {
                throw new SecurityException("Volume read rejected");
            }
            return muted && hideIndexWhenMuted ? 0 : index;
        }

        @Override
        public boolean isMuted() {
            if (failNextMuteRead) {
                failNextMuteRead = false;
                throw new SecurityException("Transient mute read failure");
            }
            if (failReadsAfterWrite && !writes.isEmpty()) {
                throw new SecurityException("Mute read rejected");
            }
            return muted;
        }

        @Override
        public boolean isFixedVolume() {
            return fixed;
        }

        @Override
        public void setVolume(int value) {
            writes.add("volume:" + value);
            if ((value == 0 && rejectZero) || (value > 0 && rejectRestore)) {
                throw new SecurityException("Volume write rejected");
            }
            if (value == 0 && ignoreZero) {
                return;
            }
            index = value;
            if (value == 0 && zeroAlsoMutes) {
                muted = true;
            }
            if (value == 0 && throwAfterZero) {
                throw new SecurityException("Failure after volume changed");
            }
        }

        @Override
        public void setMuted(boolean value) {
            writes.add("muted:" + value);
            if (rejectMute) {
                throw new SecurityException("Mute write rejected");
            }
            if (!ignoreMute) {
                muted = value;
                if (!value && failMuteReadAfterUnmute) {
                    failNextMuteRead = true;
                }
            }
        }
    }
}
