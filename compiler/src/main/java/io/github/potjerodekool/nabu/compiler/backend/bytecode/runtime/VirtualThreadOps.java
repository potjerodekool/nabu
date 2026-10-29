package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

/**
 * What {@link ThreadSupport} needs from a real {@code java.lang.VirtualThread} (one that runs on a continuation).
 * The class is package-private in the JDK, so the implementation is generated as bytecode (the continuation bridge)
 * and only exists in a program that has continuations.
 */
public interface VirtualThreadOps {

    /** VirtualThread.start(ThreadContainer). */
    void start(Thread thread, Object container);

    /** VirtualThread.threadState(). */
    Thread.State state(Thread thread);

    /** VirtualThread.alive(). */
    boolean alive(Thread thread);

    /** VirtualThread.asyncGetStackTrace(). */
    StackTraceElement[] stackTrace(Thread thread);

    /** VirtualThread.joinNanos(long): waits until the thread has ended or the time has passed. */
    boolean join(Thread thread, long nanos) throws InterruptedException;
}
