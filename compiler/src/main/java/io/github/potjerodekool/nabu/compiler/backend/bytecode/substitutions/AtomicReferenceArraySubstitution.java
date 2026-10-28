package io.github.potjerodekool.nabu.compiler.backend.bytecode.substitutions;

/**
 * {@code java.util.concurrent.atomic.AtomicReferenceArray} without the {@code VarHandle} for array elements,
 * which does not exist in a native executable. Threads run in parallel, so
 * every access takes the monitor of the atomic object (an array has no monitor: its header is only the length);
 * the field is declared here only to compile against; its owner becomes the atomic class when the
 * body is copied.
 */
public final class AtomicReferenceArraySubstitution {

    private final Object[] array = null;

    private AtomicReferenceArraySubstitution() {
    }

    public final int length() {
        return array.length;
    }

    public final Object get(final int i) {
        synchronized (this) {
            return array[i];
        }
    }

    public final void set(final int i, final Object newValue) {
        synchronized (this) {
            array[i] = newValue;
        }
    }

    public final void lazySet(final int i, final Object newValue) {
        synchronized (this) {
            array[i] = newValue;
        }
    }

    public final Object getAndSet(final int i, final Object newValue) {
        synchronized (this) {
            final Object old = array[i];
            array[i] = newValue;
            return old;
        }
    }

    public final boolean compareAndSet(final int i, final Object expectedValue, final Object newValue) {
        synchronized (this) {
            if (array[i] == expectedValue) {
                array[i] = newValue;
                return true;
            }
            return false;
        }
    }

    public final boolean weakCompareAndSet(final int i, final Object expectedValue, final Object newValue) {
        synchronized (this) {
            return compareAndSet(i, expectedValue, newValue);
        }
    }

    public final boolean weakCompareAndSetPlain(final int i, final Object expectedValue, final Object newValue) {
        synchronized (this) {
            return compareAndSet(i, expectedValue, newValue);
        }
    }

    public final Object compareAndExchange(final int i, final Object expectedValue, final Object newValue) {
        synchronized (this) {
            final Object old = array[i];
            if (old == expectedValue) {
                array[i] = newValue;
            }
            return old;
        }
    }

    public final Object getPlain(final int i) {
        synchronized (this) {
            return array[i];
        }
    }

    public final void setPlain(final int i, final Object newValue) {
        synchronized (this) {
            array[i] = newValue;
        }
    }

    public final Object getOpaque(final int i) {
        synchronized (this) {
            return array[i];
        }
    }

    public final void setOpaque(final int i, final Object newValue) {
        synchronized (this) {
            array[i] = newValue;
        }
    }

    public final Object getAcquire(final int i) {
        synchronized (this) {
            return array[i];
        }
    }

    public final void setRelease(final int i, final Object newValue) {
        synchronized (this) {
            array[i] = newValue;
        }
    }
}
