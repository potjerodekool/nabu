package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * The parts of the socket natives of the JDK that make Java objects (an InetAddress, an InetSocketAddress): the C
 * runtime (jcl_lite.c) does the sockets and answers with primitives, this class makes the objects. IPv4 only.
 * {@code sun.nio.ch.Net.accept}, {@code localInetAddress}, {@code remoteInetAddress} and
 * {@code Inet4AddressImpl.lookupAllHostAddr} are redirected here (see RuntimeCalls). Written for bytecode lowering:
 * no string concatenation with {@code +} and no lambdas.
 */
public final class NetSupport {

    private NetSupport() {
    }

    static native int fdVal0(FileDescriptor fd);

    static native void setFdVal0(FileDescriptor fd, int value);

    /** The new socket, IOStatus.UNAVAILABLE (-2) when none is waiting, IOStatus.INTERRUPTED (-3). */
    static native int accept0(int listening) throws IOException;

    /** {@code (IPv4 address << 16) | port} of the local or the remote end, -1 when there is none. */
    static native long name0(int fd, boolean remote);

    /** The IPv4 addresses of a host, 4 bytes each, null when the host is unknown. */
    static native byte[] resolve0(String host);

    private static InetAddress address(final long name) {
        final byte[] bytes = new byte[4];
        bytes[0] = (byte) (name >>> 40);
        bytes[1] = (byte) (name >>> 32);
        bytes[2] = (byte) (name >>> 24);
        bytes[3] = (byte) (name >>> 16);
        try {
            return InetAddress.getByAddress(bytes);
        } catch (final UnknownHostException e) {
            throw new IllegalStateException("not an IPv4 address");
        }
    }

    /** {@code Net.accept(FileDescriptor fd, FileDescriptor newfd, InetSocketAddress[] isaa)}. */
    public static int accept(final FileDescriptor fd, final FileDescriptor newFd, final InetSocketAddress[] isaa)
            throws IOException {
        final int socket = accept0(fdVal0(fd));
        if (socket < 0) {
            return socket;
        }
        setFdVal0(newFd, socket);
        final long name = name0(socket, true);
        isaa[0] = new InetSocketAddress(address(name), (int) (name & 0xFFFF));
        return 1;
    }

    /** {@code Net.localInetAddress(FileDescriptor fd)}. */
    public static InetAddress localInetAddress(final FileDescriptor fd) throws IOException {
        final long name = name0(fdVal0(fd), false);
        if (name < 0) {
            throw new IOException("getsockname failed");
        }
        return address(name);
    }

    /** {@code Net.remoteInetAddress(FileDescriptor fd)}. */
    public static InetAddress remoteInetAddress(final FileDescriptor fd) throws IOException {
        final long name = name0(fdVal0(fd), true);
        if (name < 0) {
            throw new IOException("getpeername failed");
        }
        return address(name);
    }

    /** {@code Inet4AddressImpl.lookupAllHostAddr(String)}; the first parameter is the Inet4AddressImpl. */
    public static InetAddress[] lookupAllHostAddr(final Object impl, final String host) throws UnknownHostException {
        final byte[] all = resolve0(host);
        if (all == null || all.length < 4) {
            throw new UnknownHostException(host);
        }
        final InetAddress[] result = new InetAddress[all.length / 4];
        for (int i = 0; i < result.length; i++) {
            final byte[] one = new byte[4];
            one[0] = all[4 * i];
            one[1] = all[4 * i + 1];
            one[2] = all[4 * i + 2];
            one[3] = all[4 * i + 3];
            result[i] = InetAddress.getByAddress(host, one);
        }
        return result;
    }
}
