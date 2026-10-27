package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.util.List;

/**
 * The messages of {@code jdk.internal.util.Preconditions} without {@code String.format}. The JDK formats them with
 * {@code String.format}, and as every index check can fail, the Formatter (with the locale providers, DecimalFormat,
 * Currency and java.time behind it) would be part of every program. The messages are the same.
 *
 * <p>Written for bytecode lowering: no string concatenation with {@code +} and no lambdas.
 */
public final class PreconditionsSupport {

    private PreconditionsSupport() {
    }

    /** Replaces {@code Preconditions.outOfBoundsMessage(String, List)}. */
    public static String outOfBoundsMessage(final String checkKind, final List<? extends Number> args) {
        if (checkKind == null && args == null) {
            return "Range check failed";
        } else if (checkKind == null) {
            return "Range check failed: ".concat(String.valueOf(args));
        } else if (args == null) {
            return "Range check failed: ".concat(checkKind);
        }
        if (checkKind.equals("checkIndex") && args.size() == 2) {
            return new StringBuilder("Index ").append(args.get(0)).append(" out of bounds for length ")
                    .append(args.get(1)).toString();
        } else if (checkKind.equals("checkFromToIndex") && args.size() == 3) {
            return new StringBuilder("Range [").append(args.get(0)).append(", ").append(args.get(1))
                    .append(") out of bounds for length ").append(args.get(2)).toString();
        } else if (checkKind.equals("checkFromIndexSize") && args.size() == 3) {
            return new StringBuilder("Range [").append(args.get(0)).append(", ").append(args.get(0)).append(" + ")
                    .append(args.get(1)).append(") out of bounds for length ").append(args.get(2)).toString();
        }
        return new StringBuilder("Range check failed: ").append(checkKind).append(' ').append(args).toString();
    }
}
