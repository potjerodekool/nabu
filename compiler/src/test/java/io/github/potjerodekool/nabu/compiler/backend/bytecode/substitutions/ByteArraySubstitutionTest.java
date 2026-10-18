package io.github.potjerodekool.nabu.compiler.backend.bytecode.substitutions;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The substituted bodies must give the same bytes as the JDK's VarHandle versions. */
class ByteArraySubstitutionTest {

    private static final long LONG = 0x0123456789abcdefL;
    private static final double DOUBLE = -1234.5678e-90;

    @Test
    void bigEndianMatchesByteBuffer() {
        final byte[] expected = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN)
                .putShort((short) 0xfedc).putChar('\uabcd').putInt(0x89abcdef).putLong(LONG)
                .putFloat(-3.25f).putDouble(DOUBLE).array();
        final byte[] actual = new byte[28];
        ByteArraySubstitution.setShort(actual, 0, (short) 0xfedc);
        ByteArraySubstitution.setChar(actual, 2, '\uabcd');
        ByteArraySubstitution.setInt(actual, 4, 0x89abcdef);
        ByteArraySubstitution.setLong(actual, 8, LONG);
        ByteArraySubstitution.setFloat(actual, 16, -3.25f);
        ByteArraySubstitution.setDouble(actual, 20, DOUBLE);
        assertArrayEquals(expected, actual);

        assertEquals((short) 0xfedc, ByteArraySubstitution.getShort(expected, 0));
        assertEquals(0xfedc, ByteArraySubstitution.getUnsignedShort(expected, 0));
        assertEquals('\uabcd', ByteArraySubstitution.getChar(expected, 2));
        assertEquals(0x89abcdef, ByteArraySubstitution.getInt(expected, 4));
        assertEquals(LONG, ByteArraySubstitution.getLong(expected, 8));
        assertEquals(-3.25f, ByteArraySubstitution.getFloat(expected, 16));
        assertEquals(DOUBLE, ByteArraySubstitution.getDouble(expected, 20));
    }

    @Test
    void littleEndianMatchesByteBuffer() {
        final byte[] expected = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) 0xfedc).putChar('\uabcd').putInt(0x89abcdef).putLong(LONG)
                .putFloat(-3.25f).putDouble(DOUBLE).array();
        final byte[] actual = new byte[28];
        ByteArrayLittleEndianSubstitution.setShort(actual, 0, (short) 0xfedc);
        ByteArrayLittleEndianSubstitution.setChar(actual, 2, '\uabcd');
        ByteArrayLittleEndianSubstitution.setInt(actual, 4, 0x89abcdef);
        ByteArrayLittleEndianSubstitution.setLong(actual, 8, LONG);
        ByteArrayLittleEndianSubstitution.setFloat(actual, 16, -3.25f);
        ByteArrayLittleEndianSubstitution.setDouble(actual, 20, DOUBLE);
        assertArrayEquals(expected, actual);

        assertEquals((short) 0xfedc, ByteArrayLittleEndianSubstitution.getShort(expected, 0));
        assertEquals('\uabcd', ByteArrayLittleEndianSubstitution.getChar(expected, 2));
        assertEquals(0x89abcdef, ByteArrayLittleEndianSubstitution.getInt(expected, 4));
        assertEquals(LONG, ByteArrayLittleEndianSubstitution.getLong(expected, 8));
        assertEquals(-3.25f, ByteArrayLittleEndianSubstitution.getFloat(expected, 16));
        assertEquals(DOUBLE, ByteArrayLittleEndianSubstitution.getDouble(expected, 20));
    }
}
