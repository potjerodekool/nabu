package io.github.potjerodekool.nabu.tools;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * A manager for files.
 */
public interface FileManager extends AutoCloseable {

    /**
     * @param location   A module orientated location.
     * @param moduleName A module name.
     * @return Returns a Location of the module.
     */
    Location getLocationForModule(Location location,
                                  String moduleName);

    /**
     * @param path      A path.
     * @param recursive If listing should be recursive or not.
     * @param kinds     The file kinds to list.
     * @return Return a list of files at the given path and kinds.
     */
    List<FileObject> listFiles(Path path,
                               boolean recursive,
                               FileObject.Kind... kinds);

    /**
     * @param location A location.
     * @return Returns a set of locations for modules.
     */
    Iterable<Set<FileManager.Location>> listLocationsForModules(StandardLocation location);

    /**
     * @param location A location.
     * @return Resolves the module name of the module at the given location.
     */
    String resolveModuleName(Location location);

    /**
     * @param location  A location.
     * @param className A class name.
     * @return Returns a fileObject at the given class.
     */
    FileObject getFileObject(Location location, String className);

    /**
     * @param location    A location
     * @param packageName A package name.
     * @param kinds       File kinds.
     * @return Returns a list of files at the given location and package with the given kinds.
     */
    Iterable<? extends FileObject> list(Location location,
                                        String packageName,
                                        Set<FileObject.Kind> kinds);

    /**
     * @param location A location.
     * @param file     A file object.
     * @return Return the binary name of the given file at the location.
     */
    String resolveBinaryName(Location location, FileObject file);

    /**
     * @param extension A file extension.
     * @return Returns the FileObject kind of the given extension if known else null.
     */
    FileObject.Kind extensionToFileObjectKind(String extension);

    /**
     * @return Returns all known FileObject kinds.
     */
    Set<FileObject.Kind> allKinds();

    /***
     * @return Returns all known FileObject kinds.
     */
    Set<FileObject.Kind> allSourceKinds();

    /**
     * @param kinds A set of FileObject kinds.
     * @return Returns a copy of the provided set.
     */
    Set<FileObject.Kind> copyOf(Set<FileObject.Kind> kinds);

    /**
     * @param compilerOptions Compiler options.
     *                        Processes the compiler options.
     */
    void processOptions(final CompilerOptions compilerOptions);

    /**
     * @param location A location.
     * @param kinds    FileObject kinds.
     * @return Returns a list of FileObject of the given kinds at the given location.
     */
    List<FileObject> getFilesForLocation(Location location,
                                         FileObject.Kind... kinds);

    /**
     * @param location A location.
     * @return Returns true if the given location is known.
     */
    boolean hasLocation(Location location);

    /**
     * A location where files can be located.
     */
    interface Location {
        /**
         * @return Returns the name of the location.
         */
        String getName();

        /**
         * @return Returns true if the location is module oriented.
         */
        default boolean isModuleOrientedLocation() {
            return getName().matches("\\bMODULE\\b");
        }

        /**
         * @return Returns true if this is an output location.
         */
        boolean isOutputLocation();

        /**
         * @return Returns true if this is a class location.
         */
        boolean isClassLocation();

        /**
         * @return Returns true if this is a source location.
         */
        boolean isSourceLocation();
    }
}
