package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.backend.ir.dataflow.analysis.Interval;
import io.github.potjerodekool.nabu.backend.ir.dataflow.analysis.IntervalAnalysis;
import io.github.potjerodekool.nabu.backend.ir.dataflow.analysis.Nullness;
import io.github.potjerodekool.nabu.backend.ir.dataflow.analysis.NullnessAnalysis;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code meet} of the nullness and the interval analysis was a copy of a map, a set of all keys and a put per key; it
 * is one pass now. The old code is here as the reference: the same map, in the same key order, for random maps.
 */
class MeetEquivalenceTest {

    private static Nullness joinNullness(final Nullness a, final Nullness b) {
        if (a == b) return a;
        return Nullness.UNKNOWN;
    }

    private static Map<String, Nullness> oldNullnessMeet(final Map<String, Nullness> a, final Map<String, Nullness> b) {
        final var r = new LinkedHashMap<>(a);
        final var keys = new LinkedHashSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (final var k : keys) {
            r.put(k, joinNullness(a.getOrDefault(k, Nullness.UNKNOWN), b.getOrDefault(k, Nullness.UNKNOWN)));
        }
        return r;
    }

    private static Interval joinInterval(final Interval a, final Interval b) {
        if (a.isBottom()) return b;
        if (b.isBottom()) return a;
        if (a.isTop() || b.isTop()) return Interval.top();
        return Interval.range(Math.min(a.lo(), b.lo()), Math.max(a.hi(), b.hi()));
    }

    private static Map<String, Interval> oldIntervalMeet(final Map<String, Interval> a, final Map<String, Interval> b) {
        if (a == b || a.equals(b)) return a;
        final var r = new LinkedHashMap<>(a);
        final var keys = new LinkedHashSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (final var k : keys) {
            r.put(k, joinInterval(a.getOrDefault(k, Interval.top()), b.getOrDefault(k, Interval.top())));
        }
        return r;
    }

    private static Nullness randomNullness(final Random random) {
        return Nullness.values()[random.nextInt(Nullness.values().length)];
    }

    private static Interval randomInterval(final Random random) {
        return switch (random.nextInt(5)) {
            case 0 -> Interval.top();
            case 1 -> Interval.bottom();
            case 2 -> Interval.point(random.nextInt(20) - 10);
            default -> {
                final long lo = random.nextInt(40) - 20;
                yield Interval.range(lo, lo + random.nextInt(30));
            }
        };
    }

    private static List<String> keys(final Map<String, ?> map) {
        return new ArrayList<>(map.keySet());
    }

    @Test
    void nullnessMeetIsTheSameAsBefore() {
        final var analysis = new NullnessAnalysis();
        final var random = new Random(42);
        for (int round = 0; round < 5000; round++) {
            final var a = new LinkedHashMap<String, Nullness>();
            final var b = new LinkedHashMap<String, Nullness>();
            for (int i = 0; i < random.nextInt(12); i++) a.put("v" + random.nextInt(15), randomNullness(random));
            for (int i = 0; i < random.nextInt(12); i++) b.put("v" + random.nextInt(15), randomNullness(random));
            final var expected = oldNullnessMeet(a, b);
            final var actual = analysis.meet(a, b);
            assertEquals(expected, actual, "a=" + a + " b=" + b);
            assertEquals(keys(expected), keys(actual), "order of the keys, a=" + a + " b=" + b);
        }
    }

    @Test
    void intervalMeetIsTheSameAsBefore() {
        final var analysis = new IntervalAnalysis();
        final var random = new Random(7);
        for (int round = 0; round < 5000; round++) {
            final var a = new LinkedHashMap<String, Interval>();
            final var b = new LinkedHashMap<String, Interval>();
            for (int i = 0; i < random.nextInt(12); i++) a.put("v" + random.nextInt(15), randomInterval(random));
            for (int i = 0; i < random.nextInt(12); i++) b.put("v" + random.nextInt(15), randomInterval(random));
            final var expected = oldIntervalMeet(a, b);
            final var actual = analysis.meet(a, b);
            assertEquals(expected, actual, "a=" + a + " b=" + b);
            assertEquals(keys(expected), keys(actual), "order of the keys, a=" + a + " b=" + b);
        }
    }
}
