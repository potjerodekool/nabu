package io.github.potjerodekool.nabu.tools;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public interface FileManager extends AutoCloseable {

    Location getLocationForModule(Location location,
                                  String moduleName);

    List<FileObject> listFiles(Path path,
                               boolean recursive,
                               FileObject.Kind... kinds);

    Iterable<Set<FileManager.Location>> listLocationsForModules(StandardLocation location);

    String resolveModuleName(Location location);

    FileObject getFileObject(Location location, String s);

    Iterable<? extends FileObject> list(Location location, String packageName, Set<FileObject.Kind> kinds);

    String resolveBinaryName(Location location, FileObject file);

    FileObject.Kind extensionToFileObjectKind(String extension);

    Set<FileObject.Kind> allKinds();

    Set<FileObject.Kind> allSourceKinds();

    Set<FileObject.Kind> copyOf(Set<FileObject.Kind> kinds);

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
