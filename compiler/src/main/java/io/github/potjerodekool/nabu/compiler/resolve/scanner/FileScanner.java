package io.github.potjerodekool.nabu.compiler.resolve.scanner;

import java.nio.file.Path;
import java.util.function.Consumer;

public interface FileScanner extends AutoCloseable {

    SearchResult findFile(String fileName);

    default SearchResult findDirectory(String directoryName) {
        return EmptyResult.INSTANCE;
    }

    default void walkDirectory(String directoryName,
                               Consumer<Path> consumer) {
    }

    default boolean hasDirectory(String directoryName) {
        return false;
    }
}
