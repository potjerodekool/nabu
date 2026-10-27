package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

/**
 * {@code String.format} for the messages the JDK itself formats (exceptions, {@code toString}): the conversions
 * {@code %s}, {@code %d}, {@code %x}, {@code %X}, {@code %c}, {@code %b}, {@code %n} and {@code %%}, with the flags
 * {@code -} and {@code 0}, a width, and {@code %<s} (the previous argument). Those are the patterns the classes of the
 * JDK use, apart from a handful (the Formatter itself, the summary statistics, {@code IllegalFormatCodePointException}).
 *
 * <p>The real Formatter brings the locale providers, DecimalFormat, Currency and java.time with it, and as these
 * messages are in the error paths of almost every class, so would every program. The compiler redirects the calls of
 * {@code String.format} in the classes of the JDK here (see BytecodeReachabilityCompiler); the program's own calls
 * stay the real thing. A conversion that is not known gives the string form of its argument.
 *
 * <p>Written for bytecode lowering: no string concatenation with {@code +} and no lambdas.
 */
public final class MiniFormat {

    private MiniFormat() {
    }

    public static String format(final String pattern, final Object... args) {
        final StringBuilder out = new StringBuilder();
        int next = 0;
        int previous = -1;
        final int length = pattern.length();
        for (int i = 0; i < length; i++) {
            final char c = pattern.charAt(i);
            if (c != '%' || i + 1 >= length) {
                out.append(c);
                continue;
            }
            i++;
            boolean reuse = false;
            boolean left = false;
            boolean zero = false;
            if (pattern.charAt(i) == '<') {
                reuse = true;
                i++;
            }
            while (i < length && (pattern.charAt(i) == '-' || pattern.charAt(i) == '0')) {
                if (pattern.charAt(i) == '-') {
                    left = true;
                } else {
                    zero = true;
                }
                i++;
            }
            int width = 0;
            while (i < length && pattern.charAt(i) >= '0' && pattern.charAt(i) <= '9') {
                width = width * 10 + (pattern.charAt(i) - '0');
                i++;
            }
            if (i >= length) {
                break;
            }
            final char conversion = pattern.charAt(i);
            if (conversion == '%') {
                out.append('%');
                continue;
            }
            if (conversion == 'n') {
                out.append('\n');
                continue;
            }
            final int index = reuse ? previous : next++;
            previous = index;
            final Object arg = index >= 0 && index < args.length ? args[index] : null;
            final String text;
            switch (conversion) {
                case 'x':
                case 'X':
                    text = hex(arg, conversion == 'X');
                    break;
                case 'c':
                    text = arg instanceof Integer ? new String(Character.toChars(((Integer) arg).intValue()))
                            : String.valueOf(arg);
                    break;
                default:
                    // %s, %d, %b and anything else: the string form of the argument
                    text = String.valueOf(arg);
                    break;
            }
            pad(out, text, width, left, zero && !left && conversion != 's');
        }
        return out.toString();
    }

    private static void pad(final StringBuilder out, final String text, final int width, final boolean left,
                            final boolean zero) {
        final int fill = width - text.length();
        if (fill <= 0) {
            out.append(text);
        } else if (left) {
            out.append(text);
            for (int i = 0; i < fill; i++) {
                out.append(' ');
            }
        } else {
            for (int i = 0; i < fill; i++) {
                out.append(zero ? '0' : ' ');
            }
            out.append(text);
        }
    }

    private static String hex(final Object arg, final boolean upper) {
        final String digits;
        if (arg instanceof Long) {
            digits = Long.toHexString(((Long) arg).longValue());
        } else if (arg instanceof Short) {
            digits = Integer.toHexString(((Short) arg).shortValue() & 0xFFFF);
        } else if (arg instanceof Byte) {
            digits = Integer.toHexString(((Byte) arg).byteValue() & 0xFF);
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
