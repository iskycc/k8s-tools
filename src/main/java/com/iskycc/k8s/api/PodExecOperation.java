package com.iskycc.k8s.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

/** 一次版本探测/执行共享总时限、取消动作和有界 SSH 输出。 */
final class PodExecOperation {
    private final long deadline;
    private final int maxOutput;
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private Runnable cancellation;
    private PodExecException failure;
    private Supplier<PodExecResult> partial;

    private PodExecOperation(PodExecOptions options) {
        deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.getTimeoutMs());
        maxOutput = options.getMaxOutputBytes();
    }

    static PodExecResult run(PodExecOptions options, Function<PodExecOperation, PodExecResult> action) {
        PodExecOperation operation = new PodExecOperation(options);
        if (Thread.currentThread().isInterrupted()) {
            throw operation.error(PodExecException.Reason.INTERRUPTED, new InterruptedException());
        }
        ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "k8s-pod-exec");
            thread.setDaemon(true);
            return thread;
        });
        Future<PodExecResult> future = worker.submit(() -> action.apply(operation));
        boolean interrupted = false;
        try {
            return future.get(operation.remainingMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw operation.stop(PodExecException.Reason.TIMEOUT, null);
        } catch (InterruptedException e) {
            interrupted = true;
            throw operation.stop(PodExecException.Reason.INTERRUPTED, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) { throw (RuntimeException) cause; }
            if (cause instanceof Error) { throw (Error) cause; }
            throw operation.error(PodExecException.Reason.TRANSPORT, cause);
        } finally {
            try {
                future.cancel(true);
                operation.cancelActive();
            } finally {
                worker.shutdownNow();
                // SSHD 关闭流程可能消费中断标记，在所有清理动作完成后恢复。
                if (interrupted) { Thread.currentThread().interrupt(); }
            }
        }
    }

    synchronized int remainingMs() {
        check();
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) { throw stop(PodExecException.Reason.TIMEOUT, null); }
        return (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos));
    }

    synchronized void onCancel(Runnable action) {
        cancellation = action;
        if (failure != null) { action.run(); }
        check();
    }

    synchronized void onPartial(Supplier<PodExecResult> result) {
        check();
        partial = result;
    }

    private synchronized void cancelActive() {
        if (cancellation != null) { cancellation.run(); }
    }

    synchronized void check() {
        if (failure != null) { throw failure; }
        if (Thread.currentThread().isInterrupted()) {
            throw stop(PodExecException.Reason.INTERRUPTED, new InterruptedException());
        }
    }

    synchronized PodExecException error(PodExecException.Reason reason, Throwable cause) {
        if (partial != null) {
            PodExecResult result = partial.get();
            return new PodExecException(reason, cause, result.getStdoutBytes(), result.getStderrBytes(), "");
        }
        return new PodExecException(reason, cause, stdout.toByteArray(), stderr.toByteArray(), "");
    }

    private synchronized PodExecException stop(PodExecException.Reason reason, Throwable cause) {
        if (failure == null) { failure = error(reason, cause); }
        cancelActive();
        return failure;
    }

    OutputStream output(boolean error) {
        ByteArrayOutputStream target = error ? stderr : stdout;
        return new OutputStream() {
            @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}, 0, 1); }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                synchronized (PodExecOperation.this) {
                    if (failure != null) { throw new IOException("Pod exec cancelled"); }
                    int accepted = (int) Math.min(length, (long) maxOutput - stdout.size() - stderr.size());
                    target.write(bytes, offset, accepted);
                    if (accepted < length) {
                        stop(PodExecException.Reason.OUTPUT_LIMIT, null);
                        throw new IOException("Pod exec output limit exceeded");
                    }
                }
            }
        };
    }

    synchronized PodExecResult result(int exitCode) {
        check();
        if (exitCode < 0) { throw error(PodExecException.Reason.TRANSPORT, null); }
        return new PodExecResult(exitCode, stdout.toByteArray(), stderr.toByteArray());
    }
}
