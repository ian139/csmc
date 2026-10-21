package dev.csmc.viewmodel;

/**
 * One server-confirmed viewmodel action, as the clip player sees it. Its identity is the playback: a new cue
 * plans afresh, the same cue keeps playing. Data comes from the server event, never from the separately
 * synchronised held stack, so a render that still shows the pre-shot stack plans the right clip.
 */
public interface ViewmodelCue {
    String action();

    /** Server-given length in seconds (reload duration), or 0 when the action has none. */
    float duration();

    /**
     * {@code fire}: the magazine after this shot; {@code reload}: the magazine when it began; -1 when unknown.
     * Selects last-round, empty-reload and Dual Beretta side clips.
     */
    int magazine();

    /** {@code reload}: rounds (shells) the server will load; else 0. */
    int rounds();

    /** A plain cue, for actions whose source has no richer playback record (and tests). */
    static ViewmodelCue of(String action, float duration, int magazine, int rounds) {
        return new Plain(action, duration, magazine, rounds);
    }

    record Plain(String action, float duration, int magazine, int rounds) implements ViewmodelCue {}
}
