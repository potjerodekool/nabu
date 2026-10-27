package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

/**
 * {@code String.format} for the error messages of a few JDK classes ({@code Integer.parseInt}, {@code Long},
 * {@code Character}, ...): the patterns {@code %s}, {@code %d}, {@code %x}, {@code %X} and {@code %%}, nothing else.
 * The real Formatter brings the locale providers, DecimalFormat, Currency and java.time with it, and as these
 * methods are used by almost every program, so would every program. The compiler redirects the calls of
 * {@code String.format} in those classes here (see BytecodeReachabilityCompiler); the application's own calls stay
 * the real thing.
 *
 * <p>Written for bytecode lowering: no string concatenation with {@code +} and no lambdas.
 */
public final class MiniFormat {

    private MiniFormat() {
    }

    public static String format(final String pattern, final Object... args) {
        final StringBuilder out = new StringBuilder();
        int next = 0;
        final int length = pattern.length();
        for (int i = 0; i < length; i++) {
            final char c = pattern.charAt(i);
            if (c != '%' || i + 1 >= length) {
                out.append(c);
                continue;
            }
            final char conversion = pattern.charAt(++i);
            switch (conversion) {
                case '%':
                    out.append('%');
                    break;
                case 'n':
                    out.append('\n');
                    break;
                case 'x':
                case 'X': {
                    final Object arg = next < args.length ? args[next++] : null;
                    out.append(hex(arg, conversion == 'X'));
                    break;
                }
                default:
                    // %s, %d and anything else: the string form of the argument
                    out.append(next < args.length ? String.valueOf(args[next++]) : "null");
                    break;
            }
        }
        return out.toString();
    }

    private static String hex(final Object arg, final boolean upper) {
        final String digits;
        if (arg instanceof Long) {
            digits = Long.toHexString(((Long) arg).longValue());
        } else if (arg instanceof Number) {
            digits = Integer.toHexString(((Number) arg).intValue());
        } else {
            return String.valueOf(arg);
        }
        if (!upper) {
            return digits;
        }
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            final char d = digits.charAt(i);
            out.append(d >= 'a' && d <= 'f' ? (char) (d - 'a' + 'A') : d);
        }
        return out.toString();
    }
}
