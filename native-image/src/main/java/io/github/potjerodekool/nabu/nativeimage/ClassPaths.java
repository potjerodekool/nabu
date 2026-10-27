package io.github.potjerodekool.nabu.nativeimage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

/** Looks for classes on a class path of jars and directories. */
final class ClassPaths {

    private ClassPaths() {
    }

    /** True when one of the entries has the class (a name with dots). */
    static boolean contains(final List<Path> classPath, final String className) {
        final String resource = className.replace('.', '/') + ".class";
        for (final Path entry : classPath) {
            if (Files.isDirectory(entry)) {
                if (Files.isRegularFile(entry.resolve(resource))) {
                    return true;
                }
            } else if (Files.isRegularFile(entry)) {
                try (var zip = new ZipFile(entry.toFile())) {
                    if (zip.getEntry(resource) != null) {
                        return true;
                    }
                } catch (final IOException ignored) {
                    // not a readable jar: it has no classes for us
                }
            }
        }
        return false;
    }
}
