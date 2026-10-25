package io.github.potjerodekool.nabu.compiler.backend.bytecode.substitutions;

import io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ChannelSupport;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.Set;

/** {@code FileChannel.open} through {@link ChannelSupport} (see {@link JavaSubstitutions}). */
public final class FileChannelSubstitution {

    private FileChannelSubstitution() {
    }

    public static FileChannel open(final Path path, final Set<? extends OpenOption> options,
                                   final FileAttribute<?>... attrs) throws IOException {
        return ChannelSupport.open(path, options);
    }

    public static FileChannel open(final Path path, final OpenOption... options) throws IOException {
        return ChannelSupport.open(path, options);
    }
}
