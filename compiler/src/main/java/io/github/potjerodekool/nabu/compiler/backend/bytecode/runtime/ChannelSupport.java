package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/**
 * Opens a {@link FileChannel} on top of {@link RandomAccessFile}: the JDK's file system provider needs the
 * native Windows NIO dispatcher, which a native executable does not have. Used by the {@code FileChannel.open}
 * and {@code Files.newByteChannel} substitutions. Written for bytecode lowering: no string concatenation with
 * {@code +} and no lambdas.
 */
public final class ChannelSupport {

    private ChannelSupport() {
    }

    public static FileChannel open(final Path path, final OpenOption[] options) throws IOException {
        final Set<OpenOption> set = new HashSet<OpenOption>();
        if (options != null) {
            for (final OpenOption option : options) {
                set.add(option);
            }
        }
        return open(path, set);
    }

    public static FileChannel open(final Path path, final Set<? extends OpenOption> options) throws IOException {
        final boolean append = options.contains(StandardOpenOption.APPEND);
        final boolean write = options.contains(StandardOpenOption.WRITE) || append;
        final File file = path.toFile();
        if (options.contains(StandardOpenOption.CREATE_NEW) && write) {
            if (file.exists()) {
                throw new FileAlreadyExistsException(path.toString());
            }
            file.createNewFile();
        } else if (!file.exists()) {
            if (write && options.contains(StandardOpenOption.CREATE)) {
                file.createNewFile();
            } else {
                throw new NoSuchFileException(path.toString());
            }
        }
        final RandomAccessFile raf = new RandomAccessFile(file, write ? "rw" : "r");
        if (write && options.contains(StandardOpenOption.TRUNCATE_EXISTING)) {
            raf.setLength(0);
        }
        final FileChannel channel = raf.getChannel();
        if (append) {
            channel.position(channel.size());
        }
        return channel;
    }
}
