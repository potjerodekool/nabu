package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

/**
 * The providers {@link ServiceLoader} finds in a native executable. The JDK looks through module layers and class
 * loaders, which a native program does not have; {@code ServiceLoader.newLookupIterator} is replaced by a call to
 * {@link #lookup}. The providers are those named in the {@code META-INF/services/<service>} files of the class path:
 * the compiler embeds those files as resources and makes the providers reachable for reflection.
 *
 * <p>Written for bytecode lowering: no string concatenation with {@code +} and no lambdas.
 */
public final class ServiceSupport {

    private ServiceSupport() {
    }

    /** The providers of a service, in class-path order, each once. */
    public static <S> Iterator<ServiceLoader.Provider<S>> lookup(final Class<S> service) {
        final String file = "META-INF/services/".concat(service.getName());
        final List<String> names = new ArrayList<>();
        for (int index = 0; ; index++) {
            final byte[] data = read(file, index);
            if (data == null) {
                break;
            }
            for (String line : new String(data, StandardCharsets.UTF_8).split("\n")) {
                final int hash = line.indexOf('#');
                if (hash >= 0) {
                    line = line.substring(0, hash);
                }
                line = line.trim();
                if (!line.isEmpty() && !names.contains(line)) {
                    names.add(line);
                }
            }
        }
        final List<ServiceLoader.Provider<S>> providers = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            providers.add(new NamedProvider<S>(service, names.get(i)));
        }
        return providers.iterator();
    }

    private static byte[] read(final String name, final int index) {
        try (InputStream in = ResourceSupport.open(name, index)) {
            if (in == null) {
                return null;
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (final IOException e) {
            return null;
        }
    }

    /** A provider named in a services file: its class is loaded and instantiated when it is asked for. */
    private static final class NamedProvider<S> implements ServiceLoader.Provider<S> {
        private final Class<S> service;
        private final String className;

        NamedProvider(final Class<S> service, final String className) {
            this.service = service;
            this.className = className;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Class<? extends S> type() {
            try {
                return (Class<? extends S>) Class.forName(className);
            } catch (final ClassNotFoundException e) {
                throw new java.util.ServiceConfigurationError(service.getName().concat(": provider ")
                        .concat(className).concat(" not found"), e);
            }
        }

        @Override
        public S get() {
            try {
                return type().getDeclaredConstructor().newInstance();
            } catch (final ReflectiveOperationException e) {
                throw new java.util.ServiceConfigurationError(service.getName().concat(": provider ")
                        .concat(className).concat(" could not be instantiated"), e);
            }
        }
    }
}
