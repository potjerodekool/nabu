package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

/**
 * What the {@code get} and {@code set} calls of a {@code MethodHandles.byteArrayViewVarHandle} become (see
 * {@code ByteArrayViewRewriter}): the access to a {@code byte[]} as a wider primitive. The first argument stands
 * in for the VarHandle: {@code "B"} for big-endian, {@code "L"} for little-endian. Written for bytecode
 * lowering: no string concatenation with {@code +} and no lambdas.
 */
public final class ByteArrayViews {

    private ByteArrayViews() {
    }

    public static long getLong(final Object order, final byte[] array, final int offset) {
        long result = 0;
        if (isBig(order)) {
            for (int i = 0; i < 8; i++) {
                result = (result << 8) | (array[offset + i] & 0xffL);
            }
        } else {
            for (int i = 7; i >= 0; i--) {
                result = (result << 8) | (array[offset + i] & 0xffL);
            }
        }
        return result;
    }

    public static int getInt(final Object order, final byte[] array, final int offset) {
        int result = 0;
        if (isBig(order)) {
            for (int i = 0; i < 4; i++) {
                result = (result << 8) | (array[offset + i] & 0xff);
            }
        } else {
            for (int i = 3; i >= 0; i--) {
                result = (result << 8) | (array[offset + i] & 0xff);
            }
        }
        return result;
    }

    public static short getShort(final Object order, final byte[] array, final int offset) {
        final int first = array[offset] & 0xff;
        final int second = array[offset + 1] & 0xff;
        return (short) (isBig(order) ? (first << 8) | second : (second << 8) | first);
    }

    public static char getChar(final Object order, final byte[] array, final int offset) {
        return (char) getShort(order, array, offset);
    }

    public static void setLong(final Object order, final byte[] array, final int offset, final long value) {
        if (isBig(order)) {
            for (int i = 0; i < 8; i++) {
                array[offset + i] = (byte) (value >>> (56 - 8 * i));
            }
        } else {
            for (int i = 0; i < 8; i++) {
                array[offset + i] = (byte) (value >>> (8 * i));
            }
        }
    }

    public static void setInt(final Object order, final byte[] array, final int offset, final int value) {
        if (isBig(order)) {
            for (int i = 0; i < 4; i++) {
                array[offset + i] = (byte) (value >>> (24 - 8 * i));
            }
        } else {
            for (int i = 0; i < 4; i++) {
                array[offset + i] = (byte) (value >>> (8 * i));
            }
        }
    }

    public static void setShort(final Object order, final byte[] array, final int offset, final short value) {
        if (isBig(order)) {
            array[offset] = (byte) (value >>> 8);
            array[offset + 1] = (byte) value;
        } else {
            array[offset] = (byte) value;
            array[offset + 1] = (byte) (value >>> 8);
        }
    }

    public static void setChar(final Object order, final byte[] array, final int offset, final char value) {
        setShort(order, array, offset, (short) value);
    }

    private static boolean isBig(final Object order) {
        return ((String) order).charAt(0) == 'B';
    }
}
