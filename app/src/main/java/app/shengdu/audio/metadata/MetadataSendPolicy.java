package app.shengdu.audio.metadata;

/**
 * Per-RAOP-session decision maker for one kind of metadata send (text or artwork).
 * Combines the receiver's advertised capability with dedup and fail-safe behavior:
 *
 * - ADVERTISED: receiver's TXT `md` includes this kind. Send on every change.
 * - UNKNOWN: TXT has no `md` at all. Probe exactly once with the first item; a success
 *   promotes the session to enabled, any failure disables this kind for the session.
 * - Any IOException/4xx/5xx/timeout disables this kind for the rest of the session;
 *   audio is never affected (enforced by the caller, which never aborts the session).
 *
 * (Receivers whose `md` explicitly lacks a kind never get a policy for it; the caller
 * skips sending entirely.)
 */
public final class MetadataSendPolicy {
    public enum Mode {
        ADVERTISED,
        UNKNOWN
    }

    private String lastKey;
    private boolean enabled;
    private boolean probed;

    public MetadataSendPolicy(Mode mode) {
        this.enabled = mode == Mode.ADVERTISED;
        this.probed = mode == Mode.ADVERTISED;
    }

    /**
     * True when an item with this dedup key should be sent now; also records the attempt.
     * A null or empty key (nothing worth sending) never sends and never consumes a probe.
     */
    public boolean shouldSend(String dedupKey) {
        if (!enabled && probed) {
            return false;
        }
        if (dedupKey == null || dedupKey.isEmpty()) {
            return false;
        }
        if (dedupKey.equals(lastKey)) {
            return false;
        }
        probed = true;
        lastKey = dedupKey;
        return true;
    }

    public void onSendSuccess() {
        enabled = true;
    }

    public void onSendFailure() {
        enabled = false;
        probed = true;
    }
}
