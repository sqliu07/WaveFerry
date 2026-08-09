package app.shengdu.audio.metadata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.shengdu.audio.metadata.MetadataSendPolicy.Mode;
import org.junit.Test;

public final class MetadataSendPolicyTest {
    @Test
    public void advertisedSendsFirstItemAndDeduplicatesResends() {
        MetadataSendPolicy policy = new MetadataSendPolicy(Mode.ADVERTISED);

        assertTrue(policy.shouldSend("one"));
        assertFalse(policy.shouldSend("one"));
    }

    @Test
    public void advertisedSendsAgainWhenItemChanges() {
        MetadataSendPolicy policy = new MetadataSendPolicy(Mode.ADVERTISED);

        assertTrue(policy.shouldSend("one"));
        assertTrue(policy.shouldSend("two"));
    }

    @Test
    public void unknownProbesExactlyOnceAndSuccessEnables() {
        MetadataSendPolicy policy = new MetadataSendPolicy(Mode.UNKNOWN);

        assertTrue(policy.shouldSend("one"));
        policy.onSendSuccess();

        assertFalse(policy.shouldSend("one"));
        assertTrue(policy.shouldSend("two"));
    }

    @Test
    public void unknownProbeFailureDisablesPermanently() {
        MetadataSendPolicy policy = new MetadataSendPolicy(Mode.UNKNOWN);

        assertTrue(policy.shouldSend("one"));
        policy.onSendFailure();

        assertFalse(policy.shouldSend("two"));
        assertFalse(policy.shouldSend("three"));
    }

    @Test
    public void advertisedFailureDisablesPermanently() {
        MetadataSendPolicy policy = new MetadataSendPolicy(Mode.ADVERTISED);

        assertTrue(policy.shouldSend("one"));
        policy.onSendFailure();

        assertFalse(policy.shouldSend("two"));
    }

    @Test
    public void emptyItemsAreNeverSentAndNeverConsumeTheProbe() {
        MetadataSendPolicy advertised = new MetadataSendPolicy(Mode.ADVERTISED);
        MetadataSendPolicy unknown = new MetadataSendPolicy(Mode.UNKNOWN);

        assertFalse(advertised.shouldSend(null));
        assertFalse(advertised.shouldSend(""));
        assertFalse(unknown.shouldSend(null));
        assertFalse(unknown.shouldSend(""));
        // An empty item must not consume the single UNKNOWN probe.
        assertTrue(unknown.shouldSend("one"));
    }
}
