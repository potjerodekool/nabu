package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Resource lookup in a native executable. This class is not used by the
 * compiler itself: its bytecode is compiled into the executable, and calls
 * to {@code Class.getResource[AsStream]}, {@code ClassLoader.getResource[s|AsStream]},
 * {@code ClassLoader.getSystemResource[s|AsStream]} and
 * {@code Module.getResourceAsStream} (in source and in JDK bytecode) are
 * redirected to its static methods (see {@code RuntimeCalls}). There is one
 * class loader, so the receiver only matters for resolving a name relative
 * to a class.
 *
 * <p>The contents come from {@link Resources#data(String, int)}, which the
 * compiler generates from the resource configuration. A URL for a resource
 * has the {@code resource:} protocol (as with GraalVM {@code native-image}).
 *
 * <p>Written for bytecode lowering: no string concatenation with {@code +}
 * (invokedynamic) and no lambdas.
 */
public final class ResourceSupport {

    private ResourceSupport() {
    }

    static InputStream open(final String name) {
        return open(name, 0);
    }

    static InputStream open(final String name, final int index) {
        final byte[] data = Resources.data(name, index);
        return data == null ? null : new ByteArrayInputStream(data);
    }

    static URL url(final String name) {
        return url(name, 0);
    }

    /**
     * {@code resource:/name} for the first occurrence of a resource,
     * {@code resource://index/name} for a later one (from another class-path
     * root).
     */
    static URL url(final String name, final int index) {
        if (Resources.data(name, index) == null) {
            return null;
        }
        try {
            return new URL("resource", index == 0 ? null : String.valueOf(index), -1, "/".concat(name),
                    new Handler());
        } catch (final MalformedURLException e) {
            return null;
        }
    }

    /** Every occurrence of a resource, in class-path order. */
    static Enumeration<URL> urls(final String name) {
        final List<URL> urls = new ArrayList<>();
        for (int index = 0; ; index++) {
            final URL url = url(name, index);
            if (url == null) {
                break;
            }
            urls.add(url);
        }
        return Collections.enumeration(urls);
    }

    /** {@code Class.resolveName}: absolute without the slash, else relative to the package. */
    static String classResourceName(final Class<?> type, final String name) {
        if (name.startsWith("/")) {
            return name.substring(1);
        }
        final String className = type.getName();
        final int dot = className.lastIndexOf('.');
        if (dot < 0) {
            return name;
        }
        return className.substring(0, dot).replace('.', '/').concat("/").concat(name);
    }

    public static InputStream classResourceAsStream(final Class<?> type, final String name) {
        return open(classResourceName(type, name));
    }

    public static URL classResource(final Class<?> type, final String name) {
        return url(classResourceName(type, name));
    }

    public static InputStream loaderResourceAsStream(final ClassLoader loader, final String name) {
        return open(name);
    }

    public static URL loaderResource(final ClassLoader loader, final String name) {
        return url(name);
    }

    public static Enumeration<URL> loaderResources(final ClassLoader loader, final String name) {
        return urls(name);
    }

    public static InputStream systemResourceAsStream(final String name) {
        return open(name);
    }

    public static URL systemResource(final String name) {
        return url(name);
    }

    public static Enumeration<URL> systemResources(final String name) {
        return urls(name);
    }

    /** The JDK reads its own data this way (e.g. the BreakIterator rules). */
    public static InputStream moduleResourceAsStream(final Module module, final String name) {
        return open(name.startsWith("/") ? name.substring(1) : name);
    }

    static final class Handler extends URLStreamHandler {

        @Override
        protected URLConnection openConnection(final URL url) {
            return new Connection(url);
        }
    }

    static final class Connection extends URLConnection {

        Connection(final URL url) {
            super(url);
        }

        @Override
        public void connect() {
            connected = true;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            final String host = getURL().getHost();
            final int index = host == null || host.isEmpty() ? 0 : Integer.parseInt(host);
            final InputStream in = open(getURL().getPath().substring(1), index);
            if (in == null) {
                throw new FileNotFoundException(getURL().toString());
            }
            return in;
        }
    }
}
