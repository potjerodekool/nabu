package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import io.github.potjerodekool.nabu.tools.FileManager;
import io.github.potjerodekool.nabu.tools.StandardLocation;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Laadt een JVM-{@code .class}-bestand op basis van zijn interne (slash-)
 * naam (bv. {@code "java/util/regex/Pattern"}) - zonder de JDK-klasse ooit
 * als Nabu-broncode te compileren.
 *
 * <p>Gebruikt een EIGEN, onafhankelijke {@code jrt:/}-bestandssysteem-
 * handle naar een echte JDK-installatie (standaard dezelfde JDK als waarop
 * de compiler zelf draait, {@code java.home}; override met
 * {@code --bytecode-jdk-home=<pad>}). Dit is BEWUST GESCHEIDEN van
 * {@code StandardLocation.SYSTEM_MODULES}
 * ({@code SystemModulesLocationHandler}), dat in dit project voor de
 * {@code -Pllvm}-build op een losse stub-resourcemap wijst
 * ({@code --system:...compiler/src/test/resources}, gebruikt voor met de
 * hand geschreven java.lang.*-stub-BRONbestanden) - hergebruik van die
 * locatie zou dat bestaande, werkende pad kunnen breken. Zie
 * BYTECODE_COMPILATION_PLAN.md fase 2.
 *
 * <p>Niet-JDK-bibliotheekklassen (indien ooit nodig) vallen terug op de
 * normale {@link FileManager} {@code CLASS_PATH}-locatie.
 */
final class BytecodeClassLoader {

    private final FileManager fileManager;
    private final Map<String, ClassNode> cache = new HashMap<>();
    private FileSystem jrtFileSystem;
    private boolean jrtInitAttempted;

    BytecodeClassLoader(final FileManager fileManager) {
        this(fileManager, null);
    }

    BytecodeClassLoader(final FileManager fileManager, final String jdkHomeOverride) {
        this.fileManager = fileManager;
        this.jdkHomeOverride = jdkHomeOverride;
    }

    private final String jdkHomeOverride;

    /**
     * @param internalName JVM-interne naam met slashes, bv.
     *                      {@code "java/util/regex/Pattern"}.
     * @return de geparste klasse, of {@code null} als ze nergens gevonden is.
     */
    ClassNode load(final String internalName) {
        return cache.computeIfAbsent(internalName, this::loadUncached);
    }

    private ClassNode loadUncached(final String internalName) {
        final byte[] bytes = readClassBytes(internalName);
        if (bytes == null) {
            return null;
        }
        final var classNode = new ClassNode();
        new ClassReader(bytes).accept(classNode, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return classNode;
    }

    private byte[] readClassBytes(final String internalName) {
        try {
            final var fromJrt = readFromJrt(internalName);
            if (fromJrt != null) {
                return fromJrt;
            }
            return readFromClassPath(internalName);
        } catch (final IOException e) {
            throw new RuntimeException(
                    "Cannot read bytecode for " + internalName, e);
        }
    }

    private byte[] readFromJrt(final String internalName) throws IOException {
        final FileSystem jrtfs = resolveJrtFileSystem();
        if (jrtfs == null) {
            return null;
        }
        // In jrt:/, klassen staan onder /modules/<module>/<pakketpad>; we
        // weten het modulenaam hier niet (en hoeven die niet te weten) -
        // de "/modules" view in jrt-fs ondersteunt ook een kortere,
        // module-loze zoekpath voor publieke API's via /packages, maar het
        // eenvoudigst is rechtstreeks over de modules-directory itereren
        // voor de eerste match. java.base beslaat verreweg de meeste JDK-
        // kernklassen (incl. java.util.regex.*), dus probeer die eerst.
        final Path javaBaseCandidate = jrtfs.getPath("/modules/java.base/" + internalName + ".class");
        if (Files.exists(javaBaseCandidate)) {
            return Files.readAllBytes(javaBaseCandidate);
        }
        final Path modulesRoot = jrtfs.getPath("/modules");
        try (var stream = Files.newDirectoryStream(modulesRoot, Files::isDirectory)) {
            for (final Path module : stream) {
                final Path candidate = module.resolve(internalName + ".class");
                if (Files.exists(candidate)) {
                    return Files.readAllBytes(candidate);
                }
            }
        }
        return null;
    }

    private byte[] readFromClassPath(final String internalName) throws IOException {
        final var fileObject = fileManager.getFileObject(StandardLocation.CLASS_PATH, internalName);
        if (fileObject == null) {
            return null;
        }
        try (final InputStream in = fileObject.openInputStream()) {
            return in.readAllBytes();
        }
    }

    private FileSystem resolveJrtFileSystem() {
        if (!jrtInitAttempted) {
            jrtInitAttempted = true;
            final Path javaHome = jdkHomeOverride != null
                    ? Paths.get(jdkHomeOverride)
                    : Paths.get(System.getProperty("java.home"));
            final Map<String, String> env = Collections.singletonMap("java.home", javaHome.toString());
            try {
                jrtFileSystem = FileSystems.newFileSystem(URI.create("jrt:/"), env);
            } catch (final IOException e) {
                throw new RuntimeException(
                        "Cannot open the jrt:/ file system for JDK home '" + javaHome
                                + "' (use --bytecode-jdk-home to choose another JDK)", e);
            }
        }
        return jrtFileSystem;
    }
}
