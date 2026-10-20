package io.github.potjerodekool.nabu.compiler.backend.bytecode.substitutions;

/**
 * {@code java.util.concurrent.atomic.AtomicLongArray} without the {@code VarHandle} for array elements, which does
 * not exist in a native executable. A native executable runs one thread, so plain reads and writes are atomic.
 * The field is declared here only to compile against; its owner becomes the atomic class when the body is copied.
 */
public final class AtomicLongArraySubstitution {

    private final long[] array = null;

    private AtomicLongArraySubstitution() {
    }

    public final int length() {
        return array.length;
    }

    public final long get(final int i) {
        return array[i];
    }

    public final void set(final int i, final long newValue) {
        array[i] = newValue;
    }

    public final void lazySet(final int i, final long newValue) {
        array[i] = newValue;
    }

    public final long getAndSet(final int i, final long newValue) {
        final long old = array[i];
        array[i] = newValue;
        return old;
    }

    public final boolean compareAndSet(final int i, final long expectedValue, final long newValue) {
        if (array[i] == expectedValue) {
            array[i] = newValue;
            return true;
        }
        return false;
    }

    public final boolean weakCompareAndSet(final int i, final long expectedValue, final long newValue) {
        return compareAndSet(i, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetPlain(final int i, final long expectedValue, final long newValue) {
        return compareAndSet(i, expectedValue, newValue);
    }

    public final long compareAndExchange(final int i, final long expectedValue, final long newValue) {
        final long old = array[i];
        if (old == expectedValue) {
            array[i] = newValue;
        }
        return old;
    }

    public final long getAndIncrement(final int i) {
        final long old = array[i];
        array[i] = (long) (old + 1);
        return old;
    }

    public final long getAndDecrement(final int i) {
        final long old = array[i];
        array[i] = (long) (old - 1);
        return old;
    }

    public final long getAndAdd(final int i, final long delta) {
        final long old = array[i];
        array[i] = (long) (old + delta);
        return old;
    }

    public final long incrementAndGet(final int i) {
        array[i] = (long) (array[i] + 1);
        return array[i];
    }

    public final long decrementAndGet(final int i) {
        array[i] = (long) (array[i] - 1);
        return array[i];
    }

    public final long addAndGet(final int i, final long delta) {
        array[i] = (long) (array[i] + delta);
        return array[i];
    }

    public final long getPlain(final int i) {
        return array[i];
    }

    public final void setPlain(final int i, final long newValue) {
        array[i] = newValue;
    }

    public final long getOpaque(final int i) {
        return array[i];
    }

    public final void setOpaque(final int i, final long newValue) {
        array[i] = newValue;
    }

    public final long getAcquire(final int i) {
        return array[i];
    }

    public final void setRelease(final int i, final long newValue) {
        array[i] = newValue;
    }
}
