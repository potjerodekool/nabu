package io.github.potjerodekool.nabu.backend.ir.dataflow.analysis;

public record Interval(long lo, long hi, boolean isTop, boolean isBottom) {
    public static Interval top() { return new Interval(0, 0, true, false); }
    public static Interval bottom() { return new Interval(0, 0, false, true); }
    public static Interval point(long v) { return new Interval(v, v, false, false); }
    public static Interval range(long lo, long hi) { return new Interval(lo, hi, false, false); }
    public boolean contains(long v) {
        if (isTop) return true;
        if (isBottom) return false;
        return v >= lo && v <= hi;
    }
}