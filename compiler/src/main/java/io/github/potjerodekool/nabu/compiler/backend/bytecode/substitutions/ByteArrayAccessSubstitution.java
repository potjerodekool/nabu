package io.github.potjerodekool.nabu.compiler.backend.bytecode.substitutions;

/**
 * {@code sun.security.provider.ByteArrayAccess} (the message digests) without {@code VarHandle}s, which
 * do not exist in a native executable: the same conversions between byte arrays and int or long arrays,
 * written with shifts.
 */
public final class ByteArrayAccessSubstitution {

    private ByteArrayAccessSubstitution() {
    }

    static void i2bLittle(final int[] in, int inOfs, final byte[] out, int outOfs, int len) {
        len += outOfs;
        while (outOfs < len) {
            final int v = in[inOfs++];
            out[outOfs++] = (byte) v;
            out[outOfs++] = (byte) (v >> 8);
            out[outOfs++] = (byte) (v >> 16);
            out[outOfs++] = (byte) (v >> 24);
        }
    }

    static void i2bLittle4(final int val, final byte[] out, final int outOfs) {
        out[outOfs] = (byte) val;
        out[outOfs + 1] = (byte) (val >> 8);
        out[outOfs + 2] = (byte) (val >> 16);
        out[outOfs + 3] = (byte) (val >> 24);
    }

    static void b2iBig(final byte[] in, int inOfs, final int[] out, int outOfs, int len) {
        len += inOfs;
        while (inOfs < len) {
            out[outOfs++] = ((in[inOfs] & 0xff) << 24) | ((in[inOfs + 1] & 0xff) << 16)
                    | ((in[inOfs + 2] & 0xff) << 8) | (in[inOfs + 3] & 0xff);
            inOfs += 4;
        }
    }

    static void b2iBig64(final byte[] in, final int inOfs, final int[] out) {
        b2iBig(in, inOfs, out, 0, 64);
    }

    static void i2bBig(final int[] in, int inOfs, final byte[] out, int outOfs, int len) {
        len += outOfs;
        while (outOfs < len) {
            final int v = in[inOfs++];
            out[outOfs++] = (byte) (v >> 24);
            out[outOfs++] = (byte) (v >> 16);
            out[outOfs++] = (byte) (v >> 8);
            out[outOfs++] = (byte) v;
        }
    }

    static void i2bBig4(final int val, final byte[] out, final int outOfs) {
        out[outOfs] = (byte) (val >> 24);
        out[outOfs + 1] = (byte) (val >> 16);
        out[outOfs + 2] = (byte) (val >> 8);
        out[outOfs + 3] = (byte) val;
    }

    static void b2lBig(final byte[] in, int inOfs, final long[] out, int outOfs, int len) {
        len += inOfs;
        while (inOfs < len) {
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (in[inOfs + i] & 0xffL);
            }
            out[outOfs++] = v;
            inOfs += 8;
        }
    }

    static void b2lBig128(final byte[] in, final int inOfs, final long[] out) {
        b2lBig(in, inOfs, out, 0, 128);
    }

    static void l2bBig(final long[] in, int inOfs, final byte[] out, int outOfs, int len) {
        len += outOfs;
        while (outOfs < len) {
            final long v = in[inOfs++];
            for (int i = 7; i >= 0; i--) {
                out[outOfs++] = (byte) (v >> (8 * i));
            }
        }
    }

    static void b2lLittle(final byte[] in, int inOfs, final long[] out, int outOfs, int len) {
        len += inOfs;
        while (inOfs < len) {
            long v = 0;
            for (int i = 7; i >= 0; i--) {
                v = (v << 8) | (in[inOfs + i] & 0xffL);
            }
            out[outOfs++] = v;
            inOfs += 8;
        }
    }

    static void l2bLittle(final long[] in, int inOfs, final byte[] out, int outOfs, int len) {
        len += outOfs;
        while (outOfs < len) {
            final long v = in[inOfs++];
            for (int i = 0; i < 8; i++) {
                out[outOfs++] = (byte) (v >> (8 * i));
            }
        }
    }
}
