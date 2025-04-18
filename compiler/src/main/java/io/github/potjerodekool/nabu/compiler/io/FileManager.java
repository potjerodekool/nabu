package io.github.potjerodekool.nabu.compiler.io;

import io.github.potjerodekool.nabu.compiler.CompilerOptions;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public interface FileManager extends AutoCloseable {

    Location getLocationForModule(Location location,
                                  String moduleName) throws IOException;

    List<FileObject> listFiles(Path path,
                               boolean recursive,
                               FileObject.Kind... kinds);

    Iterable<Set<FileManager.Location>> listLocationsForModules(StandardLocation location);

    String resolveModuleName(Location location);

    FileObject getFileObject(Location location, String s, ElementKind kind);

    Iterable<? extends FileObject> list(Location location, String packageName, EnumSet<FileObject.Kind> kinds);

    String resolveBinaryName(Location location, FileObject file);

    void processOptions(final CompilerOptions compilerOptions);

    List<FileObject> getFilesForLocation(Location location,
                                         FileObject.Kind... kinds);

    boolean hasLocation(Location location);


    interface Location {
            String getName();

            default boolean isModuleOrientedLocation() {
                return getName().matches("\\bMODULE\\b");
            }

            boolean isOutputLocation();
        }
    }
