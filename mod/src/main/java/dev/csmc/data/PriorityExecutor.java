package dev.csmc.data;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Fixed worker pool whose queue serves {@link Priority#FOREGROUND} work before {@link Priority#BACKGROUND} work, each
 * in submission order. A queued background task can be {@link Task#promote promoted} when something now needs it, so
 * speculative warm-up never delays what the player is looking at. Running tasks are never interrupted.
 */
public final class PriorityExecutor {
    public enum Priority { FOREGROUND, BACKGROUND }

    private final PriorityBlockingQueue<Runnable> queue = new PriorityBlockingQueue<>();
    private final ThreadPoolExecutor pool;
    private final AtomicLong sequence = new AtomicLong();

    public PriorityExecutor(String name, int threads) {
        pool = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, queue, runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
        // Workers exist before anything is queued, so promote() can requeue directly.
        pool.prestartAllCoreThreads();
    }

    public <T> Task<T> submit(Supplier<T> work, Priority priority) {
        Task<T> task = new Task<>(work, priority, sequence.getAndIncrement());
        pool.execute(task);
        return task;
    }

    public final class Task<T> implements Runnable, Comparable<Task<?>> {
        private final Supplier<T> work;
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private volatile Priority priority;
        private volatile long order;

        private Task(Supplier<T> work, Priority priority, long order) {
            this.work = work;
            this.priority = priority;
            this.order = order;
        }

        public CompletableFuture<T> future() { return future; }
        public Priority priority() { return priority; }

        /** Moves a still-queued background task to the back of the foreground line. No-op once started. */
        public void promote() {
            if (priority == Priority.FOREGROUND) return;
            if (queue.remove(this)) {
                priority = Priority.FOREGROUND;
                order = sequence.getAndIncrement();
                queue.add(this);
            } else {
                priority = Priority.FOREGROUND; // already running or done
            }
        }

        /**
         * Drops this task if it has not started; its future then completes with a {@link java.util.concurrent.CancellationException}.
         * Returns whether it was dropped. A running task is never interrupted.
         */
        public boolean cancelIfQueued() {
            if (!queue.remove(this)) return false;
            future.cancel(false);
            return true;
        }

        @Override public void run() {
            try {
                future.complete(work.get());
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        }

        @Override public int compareTo(Task<?> other) {
            int byPriority = priority.compareTo(other.priority);
            return byPriority != 0 ? byPriority : Long.compare(order, other.order);
        }
    }
}
