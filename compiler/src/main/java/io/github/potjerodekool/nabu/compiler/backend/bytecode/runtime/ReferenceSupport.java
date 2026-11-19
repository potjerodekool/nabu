package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.lang.ref.Reference;

/**
 * Weak and soft references in a native executable. A {@link java.lang.ref.WeakReference} or {@link java.lang.ref.SoftReference}
 * hides its referent from the collector and has the collector clear it when nothing else refers to the referent
 * ({@code GC_general_register_disappearing_link}); before this, every reference was as strong as any other pointer, so the
 * caches of a framework that are made of soft references (Spring's ConcurrentReferenceHashMap) were never emptied and a
 * server ran out of memory. A phantom reference stays strong.
 *
 * <p>Called from the code of the JDK, patched when the program is compiled (BytecodeReachabilityCompiler):
 * the constructor of {@code Reference} calls {@link #register0}, {@code Reference.get()} calls {@link #get0},
 * {@code ReferenceQueue.poll()} and {@code remove(long)} call {@link #process}. There is no reference handler thread: a
 * reference with a queue whose referent was cleared is put on its queue when the queue is asked, after a collection.
 * Written for bytecode lowering: no string concatenation with {@code +} and no lambdas.</p>
 */
public final class ReferenceSupport {

    private ReferenceSupport() {
    }

    /** The referent of the reference, null when it was cleared: the collector may clear it while this runs, hence native. */
    public static native Object get0(Object reference);

    /**
     * Makes the reference weak when it is a WeakReference or a SoftReference whose referent the collector can see; the
     * queue (null: none) tells that a cleared reference has to be put on a queue later.
     */
    public static native void register0(Object reference, Object queue);

    /** The next reference with a queue whose referent was cleared by the collector, or null (see {@link #process}). */
    private static native Object nextCleared0();

    /** Puts the references whose referent the collector cleared on their queues; cheap when no collection has run since. */
    public static void process() {
        Object next = nextCleared0();
        while (next != null) {
            ((Reference<?>) next).enqueue();
            next = nextCleared0();
        }
    }
}
