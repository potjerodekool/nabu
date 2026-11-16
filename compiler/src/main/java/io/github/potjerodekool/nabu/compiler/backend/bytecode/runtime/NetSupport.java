package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * The parts of the socket natives of the JDK that make Java objects (an InetAddress, an InetSocketAddress): the C
 * runtime (jcl_lite.c) does the sockets and answers with primitives, this class makes the objects. Every socket is of
 * the IPv6 family and takes IPv4 as well (dual stack) when the system has IPv6; IPv4 only otherwise.
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

    /**
     * The address of the local or the remote end (4 bytes for IPv4, 16 for IPv6) followed by the port (2 bytes, big
     * endian); null when there is none.
     */
    static native byte[] nameBytes0(int fd, boolean remote);

    /**
     * The addresses of a host, IPv4 ones first: each a length byte (4 or 16) and the address; null when the host is
     * unknown.
     */
    static native byte[] resolve0(String host);

    private static InetAddress address(final byte[] name) {
        final byte[] bytes = new byte[name.length - 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = name[i];
        }
        try {
            return InetAddress.getByAddress(bytes);
        } catch (final UnknownHostException e) {
            throw new IllegalStateException("not an IP address");
        }
    }

    private static int port(final byte[] name) {
        return ((name[name.length - 2] & 0xFF) << 8) | (name[name.length - 1] & 0xFF);
    }

    /** {@code Net.accept(FileDescriptor fd, FileDescriptor newfd, InetSocketAddress[] isaa)}. */
    public static int accept(final FileDescriptor fd, final FileDescriptor newFd, final InetSocketAddress[] isaa)
            throws IOException {
        final int socket = accept0(fdVal0(fd));
        if (socket < 0) {
            return socket;
        }
        setFdVal0(newFd, socket);
        final byte[] name = nameBytes0(socket, true);
        if (name == null) {
            throw new IOException("getpeername failed");
        }
        isaa[0] = new InetSocketAddress(address(name), port(name));
        return 1;
    }

    /** {@code Net.localInetAddress(FileDescriptor fd)}. */
    public static InetAddress localInetAddress(final FileDescriptor fd) throws IOException {
        final byte[] name = nameBytes0(fdVal0(fd), false);
        if (name == null) {
            throw new IOException("getsockname failed");
        }
        return address(name);
    }

    /** {@code Net.remoteInetAddress(FileDescriptor fd)}. */
    public static InetAddress remoteInetAddress(final FileDescriptor fd) throws IOException {
        final byte[] name = nameBytes0(fdVal0(fd), true);
        if (name == null) {
            throw new IOException("getpeername failed");
        }
        return address(name);
    }

    /** {@code Inet4AddressImpl.lookupAllHostAddr(String)}; the first parameter is the Inet4AddressImpl. */
    public static InetAddress[] lookupAllHostAddr(final Object impl, final String host) throws UnknownHostException {
        final byte[] all = resolve0(host);
        if (all == null || all.length < 5) {
            throw new UnknownHostException(host);
        }
        int count = 0;
        for (int at = 0; at < all.length; at += 1 + (all[at] & 0xFF)) {
            count++;
        }
        final InetAddress[] result = new InetAddress[count];
        int index = 0;
        for (int at = 0; at < all.length; at += 1 + (all[at] & 0xFF)) {
            final int size = all[at] & 0xFF;
            final byte[] one = new byte[size];
            for (int i = 0; i < size; i++) {
                one[i] = all[at + 1 + i];
            }
            result[index] = InetAddress.getByAddress(host, one);
            index++;
        }
        return result;
    }
}
