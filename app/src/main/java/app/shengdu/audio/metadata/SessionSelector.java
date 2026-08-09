package app.shengdu.audio.metadata;

import java.util.List;
import java.util.Set;

/**
 * Picks which active media session Shengdu should follow, with sticky selection so
 * the reported track does not flap between multiple players. Pure Java; the Android
 * adapter ({@link MediaSessionMonitor}) maps MediaControllers into {@link Descriptor}s.
 *
 * Rules, in order:
 * 1. Our own package is never a candidate.
 * 2. PLAYING ranks above PAUSED_OR_BUFFERING, which ranks above OTHER (stopped etc.).
 * 3. At equal rank, sessions with a non-empty title beat those without; then non-empty artist.
 * 4. Bonus packages (e.g. Apple Music) break remaining ties; list order breaks the rest.
 * 5. Sticky: the current selection is kept while it still exists and is not OTHER-ranked,
 *    unless another session is PLAYING and the sticky one is not.
 */
final class SessionSelector {
    enum State {
        PLAYING,
        PAUSED_OR_BUFFERING,
        OTHER
    }

    static final class Descriptor {
        final Object id;
        final String packageName;
        final State state;
        final boolean hasTitle;
        final boolean hasArtist;

        Descriptor(Object id, String packageName, State state, boolean hasTitle, boolean hasArtist) {
            this.id = id;
            this.packageName = packageName;
            this.state = state;
            this.hasTitle = hasTitle;
            this.hasArtist = hasArtist;
        }
    }

    private final String ownPackage;
    private final Set<String> bonusPackages;
    private Object selectedId;

    SessionSelector(String ownPackage, Set<String> bonusPackages) {
        this.ownPackage = ownPackage;
        this.bonusPackages = bonusPackages;
    }

    /** Returns the id of the session to follow, or null when there is no usable session. */
    Object update(List<Descriptor> sessions) {
        Descriptor sticky = null;
        Descriptor best = null;
        for (Descriptor candidate : sessions) {
            if (candidate == null || candidate.id == null) {
                continue;
            }
            if (candidate.packageName != null && candidate.packageName.equals(ownPackage)) {
                continue;
            }
            if (candidate.id.equals(selectedId)) {
                sticky = candidate;
            }
            if (best == null || compare(candidate, best) < 0) {
                best = candidate;
            }
        }

        if (sticky != null && sticky.state != State.OTHER) {
            boolean challengerPlaying = best != null
                    && best.state == State.PLAYING
                    && !best.id.equals(sticky.id);
            if (sticky.state == State.PLAYING || !challengerPlaying) {
                selectedId = sticky.id;
                return selectedId;
            }
        }

        selectedId = best == null ? null : best.id;
        return selectedId;
    }

    void reset() {
        selectedId = null;
    }

    /** Negative when left outranks right. */
    private int compare(Descriptor left, Descriptor right) {
        int byState = Integer.compare(rank(left.state), rank(right.state));
        if (byState != 0) {
            return byState;
        }
        int byTitle = Boolean.compare(right.hasTitle, left.hasTitle);
        if (byTitle != 0) {
            return byTitle;
        }
        int byArtist = Boolean.compare(right.hasArtist, left.hasArtist);
        if (byArtist != 0) {
            return byArtist;
        }
        return Boolean.compare(isBonus(right), isBonus(left));
    }

    private boolean isBonus(Descriptor descriptor) {
        return descriptor.packageName != null && bonusPackages.contains(descriptor.packageName);
    }

    private static int rank(State state) {
        switch (state) {
            case PLAYING:
                return 0;
            case PAUSED_OR_BUFFERING:
                return 1;
            default:
                return 2;
        }
    }
}
