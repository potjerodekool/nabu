package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Collections;
import java.util.Enumeration;

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
 * <p>The contents come from {@link Resources#data(String)}, which the
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
        final byte[] data = Resources.data(name);
        return data == null ? null : new ByteArrayInputStream(data);
    }

    static URL url(final String name) {
        if (Resources.data(name) == null) {
            return null;
        }
        try {
            return new URL("resource", null, -1, "/".concat(name), new Handler());
        } catch (final MalformedURLException e) {
            return null;
        }
    }

    static Enumeration<URL> urls(final String name) {
        final URL url = url(name);
        return Collections.enumeration(url == null
                ? Collections.<URL>emptyList()
                : Collections.singletonList(url));
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
            final InputStream in = open(getURL().getPath().substring(1));
            if (in == null) {
                throw new FileNotFoundException(getURL().toString());
            }
            return in;
        }
    }
}
