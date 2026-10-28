package io.github.potjerodekool.nabu.cli;

/** Version numbers as the releases use them: {@code 1.2.3}, with an optional {@code -SNAPSHOT} or other suffix. */
final class Versions {

    private Versions() {
    }

    /** Negative when {@code a} is older than {@code b}; a version with a suffix is older than the one without. */
    static int compare(final String a, final String b) {
        final String[] left = numbers(a);
        final String[] right = numbers(b);
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            final long x = i < left.length ? number(left[i]) : 0;
            final long y = i < right.length ? number(right[i]) : 0;
            if (x != y) {
                return Long.compare(x, y);
            }
        }
        final boolean suffixA = a.contains("-");
        final boolean suffixB = b.contains("-");
        if (suffixA != suffixB) {
            return suffixA ? -1 : 1;
        }
        return 0;
    }

    /** {@code v1.2.3} and {@code 1.2.3} are the same version. */
    static String strip(final String tag) {
        return tag.startsWith("v") ? tag.substring(1) : tag;
    }

    private static String[] numbers(final String version) {
        final int dash = version.indexOf('-');
        return (dash >= 0 ? version.substring(0, dash) : version).split("\\.");
    }

    private static long number(final String part) {
        try {
            return Long.parseLong(part);
        } catch (final NumberFormatException e) {
            return 0;
        }
    }
}
