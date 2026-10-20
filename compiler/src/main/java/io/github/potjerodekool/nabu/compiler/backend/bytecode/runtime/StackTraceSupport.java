package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

/**
 * Stack traces in a native executable. {@code Throwable.fillInStackTrace}
 * (see {@code ThrowableSubstitution}) stores the return addresses of the
 * running frames as a {@code long[]}; they are turned into
 * {@link StackTraceElement}s only when someone asks for the trace. The C
 * runtime finds the function of an address in the symbol table of the
 * executable. Functions that come from source also have a line table (see
 * {@code DebugLines}), which gives the file and line; functions compiled from
 * bytecode have none. The frames of functions that LLVM inlined are missing,
 * except for the innermost one when it has a line table. Written for bytecode
 * lowering: no string concatenation with {@code +} and no lambdas.
 */
public final class StackTraceSupport {

    /** The most frames a trace holds. */
    private static final int MAX_FRAMES = 128;

    /** The symbol prefix of this class: its own frames are not part of a trace. */
    private static final String SUPPORT_PREFIX = "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.StackTraceSupport_";

    private StackTraceSupport() {
    }

    /** The return addresses of the calling frames, innermost first, or null when they cannot be walked. */
    private static native long[] capture0(int max);

    /** "package.Owner_member" of the function that contains the address, or null when unknown. */
    private static native String frameName0(long address);

    /**
     * "symbol, file and line" separated by tabs of the source line at the address, or null.
     * The symbol is the function the line belongs to: another one than
     * {@link #frameName0} gives when that function was inlined.
     */
    private static native String frameLocation0(long address);

    public static long[] capture() {
        return capture0(MAX_FRAMES);
    }

    public static StackTraceElement[] elements(final Object backtrace) {
        if (backtrace == null) {
            return new StackTraceElement[0];
        }
        final long[] addresses = (long[]) backtrace;
        final StackTraceElement[] found = new StackTraceElement[addresses.length * 2];
        int count = 0;
        boolean leading = true;
        for (final long address : addresses) {
            final String name = frameName0(address);
            if (name == null) {
                continue;
            }
            // The program starts in the C function main; what is below it
            // (mainCRTStartup, ...) is not part of a trace.
            if ("main".equals(name)) {
                break;
            }
            String file = null;
            int line = -1;
            String scope = name;
            final String location = frameLocation0(address);
            if (location != null) {
                final int firstTab = location.indexOf('\t');
                final int lastTab = location.lastIndexOf('\t');
                scope = location.substring(0, firstTab);
                file = location.substring(firstTab + 1, lastTab);
                line = Integer.parseInt(location.substring(lastTab + 1));
            }
            // A line of an inlined function belongs to that function; the
            // symbol around the address is its caller, whose own line is lost.
            if (!scope.equals(name)) {
                final StackTraceElement inner = element(scope, file, line, leading);
                if (inner != null) {
                    found[count++] = inner;
                    leading = false;
                }
                file = null;
                line = -1;
            }
            final StackTraceElement frame = element(name, file, line, leading);
            if (frame != null) {
                found[count++] = frame;
                leading = false;
            }
        }
        final StackTraceElement[] result = new StackTraceElement[count];
        System.arraycopy(found, 0, result, 0, count);
        return result;
    }

    /** The element for a symbol "package.Owner_member", or null for a frame that is not part of the trace. */
    private static StackTraceElement element(final String symbol, final String file, final int line,
                                             final boolean leading) {
        final int split = symbol.lastIndexOf('_');
        if (split <= 0) {
            return null;
        }
        String method = symbol.substring(split + 1);
        final int overload = method.indexOf('$');
        if (overload > 0) {
            method = method.substring(0, overload);
        }
        if ("init".equals(method)) {
            method = "<init>";
        } else if ("clinit".equals(method)) {
            method = "<clinit>";
        }
        // The frames that create the exception are not part of its trace.
        if (leading && ("<init>".equals(method) || "fillInStackTrace".equals(method)
                || symbol.startsWith(SUPPORT_PREFIX))) {
            return null;
        }
        return new StackTraceElement(symbol.substring(0, split), method, file, line);
    }
}
