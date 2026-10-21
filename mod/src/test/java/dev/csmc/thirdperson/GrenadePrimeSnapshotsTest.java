package dev.csmc.thirdperson;

import static org.junit.Assert.assertEquals;

import dev.csmc.thirdperson.GrenadePrime.PendingSnapshots;
import dev.csmc.thirdperson.GrenadePrimeState.Result;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;

public class GrenadePrimeSnapshotsTest {
    private static final Object HE = new Object();

    private final UUID alice = new UUID(0, 1);
    private final UUID bob = new UUID(0, 2);
    private final Map<UUID, GrenadePrimeState> states = new HashMap<>();
    private final PendingSnapshots<String> pending = new PendingSnapshots<>();
    private final List<String> sent = new ArrayList<>();

    private GrenadePrimeState primed(UUID player) {
        GrenadePrimeState state = new GrenadePrimeState();
        assertEquals(Result.PRIME, state.prime(0, true, 0, HE));
        states.put(player, state);
        return state;
    }

    private void flush() {
        pending.flush(states, (observer, actor) -> sent.add(observer + "<-" + actor));
    }

    @Test
    public void newObserverOfPrimedActorGetsCurrentPrimeOnly() {
        primed(alice);
        // "carol" already tracks alice and saw the original prime; only "dave" starts tracking now.
        pending.add("dave", alice);
        flush();
        assertEquals(List.of("dave<-" + alice), sent);
    }

    @Test
    public void unprimedOrUnknownActorSendsNothing() {
        GrenadePrimeState state = primed(alice);
        state.cancel();
        pending.add("dave", alice);
        pending.add("dave", bob);
        flush();
        assertEquals(List.of(), sent);
    }

    @Test
    public void snapshotReflectsStateAtFlushNotAtTrackingStart() {
        GrenadePrimeState state = primed(alice);
        pending.add("dave", alice);
        // Thrown before the end-of-tick flush: the throw ended the prime, so no stale prime is sent.
        state.thrown();
        pending.add("erin", bob);
        primed(bob);
        flush();
        assertEquals(List.of("erin<-" + bob), sent);
    }

    @Test
    public void eachTrackingStartIsSentOnce() {
        primed(alice);
        pending.add("dave", alice);
        pending.add("erin", alice);
        flush();
        flush();
        assertEquals(List.of("dave<-" + alice, "erin<-" + alice), sent);
    }

    @Test
    public void retrackingResendsCurrentPrime() {
        primed(alice);
        pending.add("dave", alice);
        flush();
        // dave lost and regained alice (e.g. moved out of range); its client cleared the timeline on unload.
        pending.add("dave", alice);
        flush();
        assertEquals(List.of("dave<-" + alice, "dave<-" + alice), sent);
    }

    @Test
    public void worldChangeDropsPrimeForNewWorldObserversAndAllowsNextPrime() {
        GrenadePrimeState state = primed(alice);
        state.forgetPrime();
        pending.add("dave", alice);
        flush();
        assertEquals(List.of(), sent);

        assertEquals(Result.PRIME, state.prime(1, true, 0, HE));
        pending.add("erin", alice);
        flush();
        assertEquals(List.of("erin<-" + alice), sent);
    }

    @Test
    public void clearedQueueSendsNothing() {
        primed(alice);
        pending.add("dave", alice);
        pending.clear();
        flush();
        assertEquals(List.of(), sent);
    }
}
