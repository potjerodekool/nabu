package io.github.potjerodekool.nabu.compiler.backend.bytecode.substitutions;

/**
 * {@code sun.security.provider.MD5.implCompress0} without the {@code VarHandle} the JDK reads the block with
 * (VarHandles do not exist in a native executable). Written from RFC 1321 (section 3.4): 64 steps in four rounds,
 * with the additive constants of the RFC, the shift amounts per round and the message word order of each round.
 * The class declares {@code state} only to compile against; the body is copied into the JDK class (see
 * {@link JavaSubstitutions}).
 */
public final class MD5Substitution {

    private int[] state;

    private MD5Substitution() {
    }

    void implCompress0(final byte[] buf, final int ofs) {
        final int[] k = {
            0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee,
            0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
            0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be,
            0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
            0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa,
            0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
            0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed,
            0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
            0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c,
            0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
            0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05,
            0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
            0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039,
            0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
            0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1,
            0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391
        };
        final int[] shifts = {7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21};
        final int[] x = new int[16];
        for (int i = 0; i < 16; i++) {
            final int p = ofs + i * 4;
            x[i] = (buf[p] & 0xff) | ((buf[p + 1] & 0xff) << 8) | ((buf[p + 2] & 0xff) << 16) | ((buf[p + 3] & 0xff) << 24);
        }

        int a = state[0];
        int b = state[1];
        int c = state[2];
        int d = state[3];
        for (int i = 0; i < 64; i++) {
            final int mix;
            final int word;
            if (i < 16) {
                mix = (b & c) | (~b & d);
                word = i;
            } else if (i < 32) {
                mix = (b & d) | (c & ~d);
                word = (5 * i + 1) & 15;
            } else if (i < 48) {
                mix = b ^ c ^ d;
                word = (3 * i + 5) & 15;
            } else {
                mix = c ^ (b | ~d);
                word = (7 * i) & 15;
            }
            final int sum = a + mix + k[i] + x[word];
            final int shift = shifts[(i >> 4) * 4 + (i & 3)];
            a = d;
            d = c;
            c = b;
            b = b + ((sum << shift) | (sum >>> (32 - shift)));
        }

        state[0] += a;
        state[1] += b;
        state[2] += c;
        state[3] += d;
    }
}
