package io.github.potjerodekool.nabu.compiler.backend.bytecode.substitutions;

/**
 * {@code java.util.concurrent.atomic.AtomicIntegerArray} without the {@code VarHandle} for array elements, which does
 * not exist in a native executable. A native executable runs one thread, so plain reads and writes are atomic.
 * The field is declared here only to compile against; its owner becomes the atomic class when the body is copied.
 */
public final class AtomicIntegerArraySubstitution {

    private final int[] array = null;

    private AtomicIntegerArraySubstitution() {
    }

    public final int length() {
        return array.length;
    }

    public final int get(final int i) {
        return array[i];
    }

    public final void set(final int i, final int newValue) {
        array[i] = newValue;
    }

    public final void lazySet(final int i, final int newValue) {
        array[i] = newValue;
    }

    public final int getAndSet(final int i, final int newValue) {
        final int old = array[i];
        array[i] = newValue;
        return old;
    }

    public final boolean compareAndSet(final int i, final int expectedValue, final int newValue) {
        if (array[i] == expectedValue) {
            array[i] = newValue;
            return true;
        }
        return false;
    }

    public final boolean weakCompareAndSet(final int i, final int expectedValue, final int newValue) {
        return compareAndSet(i, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetPlain(final int i, final int expectedValue, final int newValue) {
        return compareAndSet(i, expectedValue, newValue);
    }

    public final int compareAndExchange(final int i, final int expectedValue, final int newValue) {
        final int old = array[i];
        if (old == expectedValue) {
            array[i] = newValue;
        }
        return old;
    }

    public final int getAndIncrement(final int i) {
        final int old = array[i];
        array[i] = (int) (old + 1);
        return old;
    }

    public final int getAndDecrement(final int i) {
        final int old = array[i];
        array[i] = (int) (old - 1);
        return old;
    }

    public final int getAndAdd(final int i, final int delta) {
        final int old = array[i];
        array[i] = (int) (old + delta);
        return old;
    }

    public final int incrementAndGet(final int i) {
        array[i] = (int) (array[i] + 1);
        return array[i];
    }

    public final int decrementAndGet(final int i) {
        array[i] = (int) (array[i] - 1);
        return array[i];
    }

    public final int addAndGet(final int i, final int delta) {
        array[i] = (int) (array[i] + delta);
        return array[i];
    }

    public final int getPlain(final int i) {
        return array[i];
    }

    public final void setPlain(final int i, final int newValue) {
        array[i] = newValue;
    }

    public final int getOpaque(final int i) {
        return array[i];
    }

    public final void setOpaque(final int i, final int newValue) {
        array[i] = newValue;
    }

    public final int getAcquire(final int i) {
        return array[i];
    }

    public final void setRelease(final int i, final int newValue) {
        array[i] = newValue;
    }
}
