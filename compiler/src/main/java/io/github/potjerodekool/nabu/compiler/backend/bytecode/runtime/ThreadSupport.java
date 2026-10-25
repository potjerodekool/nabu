package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

/**
 * The body of a started thread in a native executable. The C runtime creates the native thread in
 * {@code Thread.start} and calls {@link #runThread} on it. Written for bytecode lowering: no string
 * concatenation with {@code +} and no lambdas.
 */
public final class ThreadSupport {

    private ThreadSupport() {
    }

    /** Runs the thread: its {@code run} method, or the Runnable it was made with. An exception that escapes is reported. */
    public static void runThread(final Thread thread) {
        try {
            if (thread.getClass() == Thread.class) {
                final Object target = ShutdownSupport.targetOf0(thread);
                if (target instanceof Runnable runnable) {
                    runnable.run();
                }
            } else {
                ((Runnable) thread).run();
            }
        } catch (final Throwable t) {
            System.err.print("Exception in thread \"");
            System.err.print(thread.getName());
            System.err.print("\" ");
            t.printStackTrace();
        }
    }

    /** Object.wait(long) of the C runtime. */
    private static native void waitOn0(Object object, long millis) throws InterruptedException;

    public static void waitOn(final Object object) throws InterruptedException {
        waitOn0(object, 0L);
    }

    public static void waitOn(final Object object, final long millis) throws InterruptedException {
        if (millis < 0L) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        waitOn0(object, millis);
    }

    public static void waitOn(final Object object, final long millis, final int nanos) throws InterruptedException {
        if (millis < 0L) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        if (nanos < 0 || nanos > 999999) {
            throw new IllegalArgumentException("nanosecond timeout value out of range");
        }
        waitOn0(object, nanos > 0 && millis < Long.MAX_VALUE ? millis + 1L : millis);
    }

    public static void join(final Thread thread) throws InterruptedException {
        join(thread, 0L);
    }

    public static void join(final Thread thread, final long millis) throws InterruptedException {
        if (millis < 0L) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        synchronized (thread) {
            if (millis == 0L) {
                while (thread.isAlive()) {
                    waitOn0(thread, 0L);
                }
            } else {
                final long deadline = System.currentTimeMillis() + millis;
                while (thread.isAlive()) {
                    final long left = deadline - System.currentTimeMillis();
                    if (left <= 0L) {
                        break;
                    }
                    waitOn0(thread, left);
                }
            }
        }
    }

    /** 0 new, 1 started, 2 terminated. */
    private static native int stateOf0(Thread thread);

    public static Thread.State getState(final Thread thread) {
        final int state = stateOf0(thread);
        if (state == 0) {
            return Thread.State.NEW;
        }
        return state == 1 ? Thread.State.RUNNABLE : Thread.State.TERMINATED;
    }
}
