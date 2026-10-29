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

    /** The continuation (jdk.internal.vm.Continuation) of the virtual thread. */
    Object continuation(Thread thread);

    /** VirtualThread.interrupt(). */
    void interrupt(Thread thread);

    /** VirtualThread.isInterrupted(). */
    boolean isInterrupted(Thread thread);

    /** VirtualThread.getAndClearInterrupt(). */
    boolean getAndClearInterrupt(Thread thread);

    /** jdk.internal.misc.Blocker.begin(): the carrier is about to block, the scheduler may add another. */
    boolean beginBlocking();

    /** jdk.internal.misc.Blocker.end(boolean). */
    void endBlocking(boolean attempted);

    /** VirtualThread.joinNanos(long): waits until the thread has ended or the time has passed. */
    boolean join(Thread thread, long nanos) throws InterruptedException;
}
