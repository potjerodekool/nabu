package io.github.potjerodekool.nabu.compiler.backend.bytecode.substitutions;

/**
 * Method bodies for {@code jdk.internal.util.ByteArrayLittleEndian}, the
 * little-endian counterpart of {@link ByteArraySubstitution}.
 */
public final class ByteArrayLittleEndianSubstitution {

    private ByteArrayLittleEndianSubstitution() {
    }

    public static boolean getBoolean(final byte[] array, final int offset) {
        return array[offset] != 0;
    }

    public static char getChar(final byte[] array, final int offset) {
        return (char) getUnsignedShort(array, offset);
    }

    public static short getShort(final byte[] array, final int offset) {
        return (short) getUnsignedShort(array, offset);
    }

    public static int getUnsignedShort(final byte[] array, final int offset) {
        return (array[offset] & 0xff) | ((array[offset + 1] & 0xff) << 8);
    }

    public static int getInt(final byte[] array, final int offset) {
        return (array[offset] & 0xff)
                | ((array[offset + 1] & 0xff) << 8)
                | ((array[offset + 2] & 0xff) << 16)
                | ((array[offset + 3] & 0xff) << 24);
    }

    public static float getFloat(final byte[] array, final int offset) {
        return Float.intBitsToFloat(getInt(array, offset));
    }

    public static float getFloatRaw(final byte[] array, final int offset) {
        return Float.intBitsToFloat(getInt(array, offset));
    }

    public static long getLong(final byte[] array, final int offset) {
        return (getInt(array, offset) & 0xffffffffL) | ((long) getInt(array, offset + 4) << 32);
    }

    public static double getDouble(final byte[] array, final int offset) {
        return Double.longBitsToDouble(getLong(array, offset));
    }

    public static double getDoubleRaw(final byte[] array, final int offset) {
        return Double.longBitsToDouble(getLong(array, offset));
    }

    public static void setBoolean(final byte[] array, final int offset, final boolean value) {
        array[offset] = (byte) (value ? 1 : 0);
    }

    public static void setChar(final byte[] array, final int offset, final char value) {
        setUnsignedShort(array, offset, value);
    }

    public static void setShort(final byte[] array, final int offset, final short value) {
        setUnsignedShort(array, offset, value);
    }

    public static void setUnsignedShort(final byte[] array, final int offset, final int value) {
        array[offset] = (byte) value;
        array[offset + 1] = (byte) (value >> 8);
    }

    public static void setInt(final byte[] array, final int offset, final int value) {
        array[offset] = (byte) value;
        array[offset + 1] = (byte) (value >> 8);
        array[offset + 2] = (byte) (value >> 16);
        array[offset + 3] = (byte) (value >> 24);
    }

    public static void setFloat(final byte[] array, final int offset, final float value) {
        setInt(array, offset, Float.floatToIntBits(value));
    }

    public static void setFloatRaw(final byte[] array, final int offset, final float value) {
        setInt(array, offset, Float.floatToRawIntBits(value));
    }

    public static void setLong(final byte[] array, final int offset, final long value) {
        setInt(array, offset, (int) value);
        setInt(array, offset + 4, (int) (value >> 32));
    }

    public static void setDouble(final byte[] array, final int offset, final double value) {
        setLong(array, offset, Double.doubleToLongBits(value));
    }

    public static void setDoubleRaw(final byte[] array, final int offset, final double value) {
        setLong(array, offset, Double.doubleToRawLongBits(value));
    }
}
