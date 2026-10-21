package dev.csmc.viewmodel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.csmc.viewmodel.SoundCueTimeline.Scope;
import java.util.List;
import org.junit.Test;

public class SoundCueTimelineTest {
    private static List<String> payloads(List<SoundCueTimeline.Cue<String>> cues) {
        return cues.stream().map(SoundCueTimeline.Cue::payload).toList();
    }

    @Test
    public void cuesFireWhenTheirTickArrives() {
        SoundCueTimeline<String> timeline = new SoundCueTimeline<>();
        timeline.schedule(1, Scope.MAIN_HAND, 0, "now");
        timeline.schedule(1, Scope.MAIN_HAND, 2, "later");
        assertEquals(List.of("now"), payloads(timeline.drain(false)));
        assertEquals(List.of(), payloads(timeline.advance(false)));
        assertEquals(List.of("later"), payloads(timeline.advance(false)));
        assertEquals(List.of(), payloads(timeline.advance(false)));
    }

    @Test
    public void pauseFreezesTheClockAndDispatch() {
        SoundCueTimeline<String> timeline = new SoundCueTimeline<>();
        timeline.schedule(1, Scope.MAIN_HAND, 0, "now");
        timeline.schedule(1, Scope.MAIN_HAND, 1, "next");
        assertEquals(List.of(), payloads(timeline.drain(true)));
        for (int i = 0; i < 10; i++) assertEquals(List.of(), payloads(timeline.advance(true)));
        assertEquals(0, timeline.tick());
        assertEquals(List.of("now"), payloads(timeline.drain(false)));
        assertEquals(List.of("next"), payloads(timeline.advance(false)));
        assertEquals(1, timeline.tick());
    }

    @Test
    public void cancelDropsPendingAndInvalidatesInFlightCues() {
        SoundCueTimeline<String> timeline = new SoundCueTimeline<>();
        timeline.schedule(1, Scope.MAIN_HAND, 0, "decoding");
        timeline.schedule(1, Scope.MAIN_HAND, 5, "scheduled");
        timeline.schedule(2, Scope.MAIN_HAND, 0, "other");
        SoundCueTimeline.Cue<String> inFlight = timeline.drain(false).get(0);
        assertTrue(timeline.current(inFlight));
        timeline.cancelAll(1);
        assertFalse(timeline.current(inFlight));
        timeline.requeue(inFlight);
        for (int i = 0; i < 10; i++) assertEquals(List.of(), payloads(timeline.advance(false)));
    }

    @Test
    public void newCuesAfterCancelAreCurrent() {
        SoundCueTimeline<String> timeline = new SoundCueTimeline<>();
        timeline.schedule(1, Scope.MAIN_HAND, 0, "old");
        SoundCueTimeline.Cue<String> old = timeline.drain(false).get(0);
        timeline.cancel(1, Scope.MAIN_HAND);
        timeline.schedule(1, Scope.MAIN_HAND, 0, "new");
        SoundCueTimeline.Cue<String> fresh = timeline.drain(false).get(0);
        assertTrue(timeline.current(fresh));
        assertFalse(timeline.current(old));
    }

    @Test
    public void handCancelKeepsEventCues() {
        SoundCueTimeline<String> timeline = new SoundCueTimeline<>();
        timeline.schedule(1, Scope.EVENT, 0, "event");
        timeline.schedule(1, Scope.MAIN_HAND, 0, "marker");
        List<SoundCueTimeline.Cue<String>> inFlight = timeline.drain(false);
        timeline.schedule(1, Scope.EVENT, 3, "pending event");
        timeline.schedule(1, Scope.MAIN_HAND, 3, "pending marker");
        timeline.cancel(1, Scope.MAIN_HAND);
        assertTrue(timeline.current(inFlight.get(0)));
        assertFalse(timeline.current(inFlight.get(1)));
        timeline.advance(false);
        timeline.advance(false);
        assertEquals(List.of("pending event"), payloads(timeline.advance(false)));
    }

    @Test
    public void mainHandCancelPreservesOffHandPendingAndInFlightCues() {
        SoundCueTimeline<String> timeline = new SoundCueTimeline<>();
        timeline.schedule(1, Scope.OFF_HAND, 0, "inject decoding");
        timeline.schedule(1, Scope.MAIN_HAND, 0, "draw decoding");
        List<SoundCueTimeline.Cue<String>> inFlight = timeline.drain(false);
        timeline.schedule(1, Scope.OFF_HAND, 2, "inject thud");
        timeline.schedule(1, Scope.MAIN_HAND, 2, "draw tail");
        timeline.cancel(1, Scope.MAIN_HAND);
        assertTrue(timeline.current(inFlight.get(0)));
        assertFalse(timeline.current(inFlight.get(1)));
        timeline.requeue(inFlight.get(0));
        timeline.requeue(inFlight.get(1));
        assertEquals(List.of("inject decoding"), payloads(timeline.drain(false)));
        timeline.advance(false);
        assertEquals(List.of("inject thud"), payloads(timeline.advance(false)));
    }

    @Test
    public void offHandCancelPreservesMainHand() {
        SoundCueTimeline<String> timeline = new SoundCueTimeline<>();
        timeline.schedule(1, Scope.MAIN_HAND, 1, "draw");
        timeline.schedule(1, Scope.OFF_HAND, 1, "inject");
        timeline.cancel(1, Scope.OFF_HAND);
        assertEquals(List.of("draw"), payloads(timeline.advance(false)));
    }

    @Test
    public void cancelAllRetiresEveryScope() {
        SoundCueTimeline<String> timeline = new SoundCueTimeline<>();
        timeline.schedule(1, Scope.EVENT, 0, "event");
        timeline.schedule(1, Scope.MAIN_HAND, 0, "main");
        timeline.schedule(1, Scope.OFF_HAND, 0, "off");
        List<SoundCueTimeline.Cue<String>> inFlight = timeline.drain(false);
        timeline.schedule(1, Scope.OFF_HAND, 1, "pending");
        timeline.cancelAll(1);
        for (SoundCueTimeline.Cue<String> cue : inFlight) assertFalse(timeline.current(cue));
        assertEquals(List.of(), payloads(timeline.advance(false)));
    }

    @Test
    public void otherOwnersAreUntouched() {
        SoundCueTimeline<String> timeline = new SoundCueTimeline<>();
        timeline.schedule(2, Scope.MAIN_HAND, 1, "other");
        timeline.cancelAll(1);
        timeline.cancel(1, Scope.MAIN_HAND);
        assertEquals(List.of("other"), payloads(timeline.advance(false)));
    }

    @Test
    public void requeuedCurrentCueWaitsForUnpause() {
        SoundCueTimeline<String> timeline = new SoundCueTimeline<>();
        timeline.schedule(1, Scope.MAIN_HAND, 0, "decoded");
        SoundCueTimeline.Cue<String> inFlight = timeline.drain(false).get(0);
        timeline.requeue(inFlight);
        assertEquals(List.of(), payloads(timeline.drain(true)));
        assertEquals(List.of("decoded"), payloads(timeline.drain(false)));
    }

    @Test
    public void clearInvalidatesEverything() {
        SoundCueTimeline<String> timeline = new SoundCueTimeline<>();
        timeline.schedule(1, Scope.EVENT, 0, "event");
        SoundCueTimeline.Cue<String> inFlight = timeline.drain(false).get(0);
        timeline.schedule(1, Scope.OFF_HAND, 1, "pending");
        timeline.clear();
        assertFalse(timeline.current(inFlight));
        assertEquals(List.of(), payloads(timeline.advance(false)));
        timeline.schedule(1, Scope.EVENT, 0, "fresh");
        assertTrue(timeline.current(timeline.drain(false).get(0)));
    }
}
