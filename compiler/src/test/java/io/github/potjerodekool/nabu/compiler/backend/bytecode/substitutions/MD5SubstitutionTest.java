package io.github.potjerodekool.nabu.compiler.backend.bytecode.substitutions;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The block function of {@link MD5Substitution} against the MD5 of the JDK: the RFC 1321 test suite, and random
 * messages of every length around a block boundary.
 */
class MD5SubstitutionTest {

    /** MD5 of a message with the substituted block function and the padding of RFC 1321 (section 3.1 and 3.2). */
    private static String md5(final byte[] message) throws Exception {
        final Constructor<MD5Substitution> constructor = MD5Substitution.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        final MD5Substitution md5 = constructor.newInstance();
        final Field state = MD5Substitution.class.getDeclaredField("state");
        state.setAccessible(true);
        state.set(md5, new int[]{0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476});
        final Method compress = MD5Substitution.class.getDeclaredMethod("implCompress0", byte[].class, int.class);
        compress.setAccessible(true);

        final int padding = (message.length % 64 < 56 ? 56 : 120) - message.length % 64;
        final byte[] padded = new byte[message.length + padding + 8];
        System.arraycopy(message, 0, padded, 0, message.length);
        padded[message.length] = (byte) 0x80;
        final long bits = (long) message.length * 8;
        for (int i = 0; i < 8; i++) {
            padded[padded.length - 8 + i] = (byte) (bits >>> (8 * i));
        }
        for (int offset = 0; offset < padded.length; offset += 64) {
            compress.invoke(md5, padded, offset);
        }

        final int[] words = (int[]) state.get(md5);
        final byte[] digest = new byte[16];
        for (int i = 0; i < 16; i++) {
            digest[i] = (byte) (words[i / 4] >>> (8 * (i % 4)));
        }
        return HexFormat.of().formatHex(digest);
    }

    @Test
    void theTestSuiteOfRfc1321() throws Exception {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5(new byte[0]));
        assertEquals("0cc175b9c0f1b6a831c399e269772661", md5("a".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("900150983cd24fb0d6963f7d28e17f72", md5("abc".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("f96b697d7cb7938d525a2f31aaf161d0", md5("message digest".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("c3fcd3d76192e4007dfb496cca67e13b",
                md5("abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("57edf4a22be3c955ac49da2e2107b67a", md5(
                "12345678901234567890123456789012345678901234567890123456789012345678901234567890"
                        .getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void theSameDigestAsTheJdkForEveryLengthAroundABlockBoundary() throws Exception {
        final Random random = new Random(1321);
        for (int length = 0; length <= 200; length++) {
            final byte[] message = new byte[length];
            random.nextBytes(message);
            final String expected = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(message));
            assertEquals(expected, md5(message), "length " + length);
        }
    }
}
