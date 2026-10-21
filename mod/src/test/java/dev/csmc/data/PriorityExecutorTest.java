package dev.csmc.data;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class PriorityExecutorTest {
    @Test public void foregroundRunsBeforeQueuedBackgroundAndPromotedTasksJumpTheWarmUp() throws Exception {
        PriorityExecutor executor = new PriorityExecutor("test-loader", 1);
        CountDownLatch blocker = new CountDownLatch(1), started = new CountDownLatch(1);
        List<String> ran = new CopyOnWriteArrayList<>();
        var busy = executor.submit(() -> {
            started.countDown();
            try { blocker.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            return "busy";
        }, PriorityExecutor.Priority.FOREGROUND);
        started.await();
        var warmA = executor.submit(() -> ran.add("warmA"), PriorityExecutor.Priority.BACKGROUND);
        var warmB = executor.submit(() -> ran.add("warmB"), PriorityExecutor.Priority.BACKGROUND);
        var warmC = executor.submit(() -> ran.add("warmC"), PriorityExecutor.Priority.BACKGROUND);
        var held = executor.submit(() -> ran.add("held"), PriorityExecutor.Priority.FOREGROUND);
        warmC.promote(); // e.g. a reel tile needs this model now
        assertEquals(PriorityExecutor.Priority.FOREGROUND, warmC.priority());
        blocker.countDown();
        for (var task : Arrays.asList(busy, warmA, warmB, warmC, held)) task.future().get(5, TimeUnit.SECONDS);
        assertEquals(List.of("held", "warmC", "warmA", "warmB"), ran);
    }

    @Test public void cancelDropsQueuedTasksButNeverRunningOnes() throws Exception {
        PriorityExecutor executor = new PriorityExecutor("test-loader", 1);
        CountDownLatch blocker = new CountDownLatch(1), started = new CountDownLatch(1);
        List<String> ran = new CopyOnWriteArrayList<>();
        var running = executor.submit(() -> {
            started.countDown();
            try { blocker.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            return ran.add("running");
        }, PriorityExecutor.Priority.FOREGROUND);
        started.await();
        var queued = executor.submit(() -> ran.add("queued"), PriorityExecutor.Priority.BACKGROUND);
        var kept = executor.submit(() -> ran.add("kept"), PriorityExecutor.Priority.BACKGROUND);
        assertEquals(false, running.cancelIfQueued());
        assertEquals(true, queued.cancelIfQueued());
        assertEquals(true, queued.future().isCancelled());
        blocker.countDown();
        running.future().get(5, TimeUnit.SECONDS);
        kept.future().get(5, TimeUnit.SECONDS);
        assertEquals(List.of("running", "kept"), ran);
    }

    @Test public void failuresCompleteTheFutureExceptionally() throws Exception {
        PriorityExecutor executor = new PriorityExecutor("test-loader", 1);
        var task = executor.submit(() -> { throw new IllegalStateException("boom"); }, PriorityExecutor.Priority.FOREGROUND);
        try {
            task.future().join();
        } catch (java.util.concurrent.CompletionException e) {
            assertEquals("boom", e.getCause().getMessage());
            return;
        }
        throw new AssertionError("expected failure");
    }

    @Test public void warmUpOrderIsHeldThenHotbarThenRestOnce() {
        List<String> all = List.of("ak47", "awp", "deagle", "glock", "m4a1");
        assertEquals(List.of("glock", "awp", "deagle", "ak47", "m4a1"),
                ModelWarmUp.order("glock", Arrays.asList(null, "awp", "glock", "deagle", "unknown"), all));
        assertEquals(all, ModelWarmUp.order(null, List.of(), all));
    }
}
