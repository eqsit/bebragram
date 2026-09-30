package tw.nekomimi.nekogram.tor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Bounded I/O budget, including DNS (which Socket/URLConnection timeouts do not cover). */
public final class TorBridgeTasks {
    private TorBridgeTasks() {}
    // A stuck platform DNS resolver must not create an unbounded number of threads or queued jobs.
    private static final ExecutorService IO = new ThreadPoolExecutor(0, 16, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> {
                Thread thread = new Thread(task, "TorBridgeIO");
                thread.setDaemon(true);
                return thread;
            });

    public static <T> List<T> collect(List<Callable<T>> tasks, long timeoutMs, int limit) {
        return collect(tasks, timeoutMs, limit, () -> false);
    }

    public static <T> List<T> collect(List<Callable<T>> tasks, long timeoutMs, int limit, BooleanSupplier cancelled) {
        CompletionService<T> completed = new ExecutorCompletionService<>(IO);
        List<Future<T>> pending = new ArrayList<>();
        List<T> results = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            for (Callable<T> task : tasks) {
                if (cancelled.getAsBoolean()) break;
                try { pending.add(completed.submit(task)); }
                catch (RejectedExecutionException ignored) { /* Fail quickly when the bounded pool is full. */ }
            }
            int remaining = pending.size();
            while (remaining > 0 && results.size() < limit && !cancelled.getAsBoolean()) {
                long budget = deadline - System.nanoTime();
                if (budget <= 0) break;
                Future<T> future = completed.poll(Math.min(budget, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
                if (future == null) continue;
                remaining--;
                try {
                    T result = future.get();
                    if (result != null) results.add(result);
                } catch (ExecutionException | CancellationException ignored) { }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            for (Future<T> task : pending) task.cancel(true);
        }
        return results;
    }
}
