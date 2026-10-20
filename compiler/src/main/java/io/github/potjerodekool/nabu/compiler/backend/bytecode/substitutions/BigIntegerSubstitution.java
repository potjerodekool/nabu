package io.github.potjerodekool.nabu.compiler.backend.bytecode.substitutions;

import java.math.BigInteger;

/**
 * {@code BigInteger}'s Toom-Cook multiplication and squaring without {@code ForkJoinPool}: the JDK runs the
 * sub-products as fork/join tasks, which need threads and {@code VarHandle}s that a native executable does not
 * have. A Karatsuba step with the public operations gives the same product; the sub-products are smaller, so
 * they fall below the Toom-Cook threshold. The class extends {@code BigInteger} only so that {@code this} has
 * that type; it is never instantiated.
 */
public final class BigIntegerSubstitution extends BigInteger {

    private static final long serialVersionUID = 1L;

    private BigIntegerSubstitution() {
        super("0");
    }

    private static BigInteger multiplyToomCook3(final BigInteger a, final BigInteger b, final boolean parallel,
                                                final int depth) {
        final int sign = a.signum() * b.signum();
        final BigInteger x = a.abs();
        final BigInteger y = b.abs();
        final int half = (Math.max(x.bitLength(), y.bitLength()) + 1) / 2;
        final BigInteger xHigh = x.shiftRight(half);
        final BigInteger xLow = x.subtract(xHigh.shiftLeft(half));
        final BigInteger yHigh = y.shiftRight(half);
        final BigInteger yLow = y.subtract(yHigh.shiftLeft(half));
        final BigInteger high = xHigh.multiply(yHigh);
        final BigInteger low = xLow.multiply(yLow);
        final BigInteger middle = xHigh.add(xLow).multiply(yHigh.add(yLow)).subtract(high).subtract(low);
        final BigInteger product = high.shiftLeft(2 * half).add(middle.shiftLeft(half)).add(low);
        return sign < 0 ? product.negate() : product;
    }

    private BigInteger squareToomCook3(final boolean parallel, final int depth) {
        final BigInteger x = this.abs();
        final int half = (x.bitLength() + 1) / 2;
        final BigInteger high = x.shiftRight(half);
        final BigInteger low = x.subtract(high.shiftLeft(half));
        final BigInteger highSquare = high.multiply(high);
        final BigInteger lowSquare = low.multiply(low);
        final BigInteger middle = high.add(low).multiply(high.add(low)).subtract(highSquare).subtract(lowSquare);
        return highSquare.shiftLeft(2 * half).add(middle.shiftLeft(half)).add(lowSquare);
    }
}
