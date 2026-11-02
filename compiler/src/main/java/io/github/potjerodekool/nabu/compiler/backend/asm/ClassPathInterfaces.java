package io.github.potjerodekool.nabu.compiler.backend.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Tells whether a class on the class path (directories and jars) is an interface, from its class file. An
 * invokestatic or invokespecial on an interface needs an InterfaceMethodref; the JDK classes are checked through the
 * platform class loader, but the interfaces of other libraries (Spring's
 * <code>InstanceSupplier.using(...)</code>) were not known and the JVM refused the call
 * (IncompatibleClassChangeError: must be InterfaceMethodref constant).
 */
final class ClassPathInterfaces implements AutoCloseable {

    private final List<Path> entries;
    private final Map<Path, ZipFile> jars = new HashMap<>();
    private final Map<String, Boolean> results = new HashMap<>();

    ClassPathInterfaces(final List<Path> entries) {
        this.entries = new ArrayList<>(entries);
    }

    boolean isInterface(final String internalName) {
        return results.computeIfAbsent(internalName, this::lookup);
    }

    private boolean lookup(final String internalName) {
        final var fileName = internalName + ".class";
        for (final var entry : entries) {
            try {
                if (Files.isDirectory(entry)) {
                    final var file = entry.resolve(fileName);
                    if (Files.isRegularFile(file)) {
                        return isInterface(Files.readAllBytes(file));
                    }
                } else if (Files.isRegularFile(entry)) {
                    final var jar = jars.computeIfAbsent(entry, this::openJar);
                    if (jar == null) {
                        continue;
                    }
                    final var zipEntry = jar.getEntry(fileName);
                    if (zipEntry != null) {
                        try (var in = jar.getInputStream(zipEntry)) {
                            return isInterface(in.readAllBytes());
                        }
                    }
                }
            } catch (final IOException ignored) {
                // an unreadable entry is skipped, like a missing one
            }
        }
        return false;
    }

    private ZipFile openJar(final Path path) {
        try {
            return new ZipFile(path.toFile());
        } catch (final IOException e) {
            return null;
        }
    }

    private static boolean isInterface(final byte[] classFile) {
        return (new ClassReader(classFile).getAccess() & Opcodes.ACC_INTERFACE) != 0;
    }

    @Override
    public void close() {
        for (final var jar : jars.values()) {
            if (jar != null) {
                try {
                    jar.close();
                } catch (final IOException ignored) {
                    // nothing to do
                }
            }
        }
        jars.clear();
    }
}
