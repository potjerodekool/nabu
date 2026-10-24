package io.github.potjerodekool.nabu.compiler.backend.bytecode.substitutions;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ListDirectoryStream;
import io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ReaderCloser;
import java.util.ArrayList;
import java.util.List;

/**
 * The common {@code java.nio.file.Files} methods on top of {@code java.io.File}. The JDK's own
 * implementation needs the NIO natives of the platform ({@code sun.nio.fs.WindowsNativeDispatcher}
 * and friends), which a native executable does not have. Each method here replaces the
 * {@code Files} method with the same name and descriptor (see {@link JavaSubstitutions}); the
 * {@code Path} is converted with {@code toFile()}. Links, file attributes, file channels,
 * {@code walk} and {@code list} are not covered. Written for bytecode lowering: no string
 * concatenation with {@code +} and no lambdas.
 */
public final class FilesSubstitution {

    private FilesSubstitution() {
    }

    // ---- tests

    public static boolean exists(final Path path, final LinkOption... options) {
        return path.toFile().exists();
    }

    public static boolean notExists(final Path path, final LinkOption... options) {
        return !path.toFile().exists();
    }

    public static boolean isRegularFile(final Path path, final LinkOption... options) {
        return path.toFile().isFile();
    }

    public static boolean isDirectory(final Path path, final LinkOption... options) {
        return path.toFile().isDirectory();
    }

    public static boolean isReadable(final Path path) {
        return path.toFile().canRead();
    }

    public static boolean isWritable(final Path path) {
        return path.toFile().canWrite();
    }

    public static boolean isExecutable(final Path path) {
        return path.toFile().canExecute();
    }

    public static boolean isHidden(final Path path) throws IOException {
        return path.toFile().isHidden();
    }

    public static boolean isSymbolicLink(final Path path) {
        return false;
    }

    public static long size(final Path path) throws IOException {
        final File file = path.toFile();
        if (!file.exists()) {
            throw new NoSuchFileException(path.toString());
        }
        return file.length();
    }

    // ---- reading

    public static InputStream newInputStream(final Path path, final OpenOption... options) throws IOException {
        final File file = path.toFile();
        if (!file.exists()) {
            throw new NoSuchFileException(path.toString());
        }
        try {
            return new FileInputStream(file);
        } catch (final FileNotFoundException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    public static byte[] readAllBytes(final Path path) throws IOException {
        final InputStream in = newInputStream(path);
        try {
            return in.readAllBytes();
        } finally {
            in.close();
        }
    }

    public static String readString(final Path path) throws IOException {
        return new String(readAllBytes(path), StandardCharsets.UTF_8);
    }

    public static String readString(final Path path, final Charset cs) throws IOException {
        return new String(readAllBytes(path), cs);
    }

    public static BufferedReader newBufferedReader(final Path path) throws IOException {
        return newBufferedReader(path, StandardCharsets.UTF_8);
    }

    public static BufferedReader newBufferedReader(final Path path, final Charset cs) throws IOException {
        return new BufferedReader(new InputStreamReader(newInputStream(path), cs));
    }

    public static List<String> readAllLines(final Path path) throws IOException {
        return readAllLines(path, StandardCharsets.UTF_8);
    }

    public static List<String> readAllLines(final Path path, final Charset cs) throws IOException {
        final BufferedReader reader = newBufferedReader(path, cs);
        try {
            final List<String> lines = new ArrayList<String>();
            String line = reader.readLine();
            while (line != null) {
                lines.add(line);
                line = reader.readLine();
            }
            return lines;
        } finally {
            reader.close();
        }
    }

    public static java.util.stream.Stream<String> lines(final Path path) throws IOException {
        return lines(path, StandardCharsets.UTF_8);
    }

    public static java.util.stream.Stream<String> lines(final Path path, final Charset cs) throws IOException {
        final BufferedReader reader = newBufferedReader(path, cs);
        return reader.lines().onClose(new ReaderCloser(reader));
    }

    public static java.util.stream.Stream<Path> list(final Path dir) throws IOException {
        final File file = dir.toFile();
        if (!file.isDirectory()) {
            throw new NoSuchFileException(dir.toString());
        }
        final List<Path> entries = new ArrayList<Path>();
        final String[] names = file.list();
        if (names != null) {
            for (final String name : names) {
                entries.add(dir.resolve(name));
            }
        }
        return entries.stream();
    }

    public static DirectoryStream<Path> newDirectoryStream(final Path dir) throws IOException {
        final File file = dir.toFile();
        if (!file.isDirectory()) {
            throw new NoSuchFileException(dir.toString());
        }
        final List<Path> entries = new ArrayList<Path>();
        final String[] names = file.list();
        if (names != null) {
            for (final String name : names) {
                entries.add(dir.resolve(name));
            }
        }
        return new ListDirectoryStream(entries);
    }

    public static java.util.stream.Stream<Path> walk(final Path start, final java.nio.file.FileVisitOption... options)
            throws IOException {
        return walk(start, Integer.MAX_VALUE, options);
    }

    public static java.util.stream.Stream<Path> walk(final Path start, final int maxDepth,
                                                     final java.nio.file.FileVisitOption... options) throws IOException {
        if (!start.toFile().exists()) {
            throw new NoSuchFileException(start.toString());
        }
        // Depth first, in the order of Files.walk: a directory before its entries. An explicit stack, because a
        // helper method of this class would not be copied into Files.
        final List<Path> found = new ArrayList<Path>();
        final List<Path> pending = new ArrayList<Path>();
        final List<Integer> depths = new ArrayList<Integer>();
        pending.add(start);
        depths.add(0);
        while (!pending.isEmpty()) {
            final int last = pending.size() - 1;
            final Path path = pending.remove(last);
            final int depth = depths.remove(last);
            found.add(path);
            final File file = path.toFile();
            if (depth < maxDepth && file.isDirectory()) {
                final String[] names = file.list();
                if (names != null) {
                    for (int k = names.length - 1; k >= 0; k--) {
                        pending.add(path.resolve(names[k]));
                        depths.add(depth + 1);
                    }
                }
            }
        }
        return found.stream();
    }

    public static java.nio.file.attribute.FileTime getLastModifiedTime(final Path path, final LinkOption... options)
            throws IOException {
        final File file = path.toFile();
        if (!file.exists()) {
            throw new NoSuchFileException(path.toString());
        }
        return java.nio.file.attribute.FileTime.fromMillis(file.lastModified());
    }

    public static boolean isSameFile(final Path path, final Path path2) throws IOException {
        return path.toFile().getCanonicalPath().equals(path2.toFile().getCanonicalPath());
    }

    // ---- writing

    public static OutputStream newOutputStream(final Path path, final OpenOption... options) throws IOException {
        final File file = path.toFile();
        boolean append = false;
        boolean createNew = false;
        boolean create = options.length == 0;
        for (final OpenOption option : options) {
            if (option == StandardOpenOption.APPEND) {
                append = true;
            } else if (option == StandardOpenOption.CREATE_NEW) {
                createNew = true;
            } else if (option == StandardOpenOption.CREATE) {
                create = true;
            }
        }
        if (createNew && file.exists()) {
            throw new FileAlreadyExistsException(path.toString());
        }
        if (!create && !createNew && !file.exists()) {
            throw new NoSuchFileException(path.toString());
        }
        try {
            return new FileOutputStream(file, append);
        } catch (final FileNotFoundException e) {
            throw new NoSuchFileException(path.toString());
        }
    }

    public static Path write(final Path path, final byte[] bytes, final OpenOption... options) throws IOException {
        final OutputStream out = newOutputStream(path, options);
        try {
            out.write(bytes, 0, bytes.length);
        } finally {
            out.close();
        }
        return path;
    }

    public static Path writeString(final Path path, final CharSequence csq, final OpenOption... options)
            throws IOException {
        return write(path, csq.toString().getBytes(StandardCharsets.UTF_8), options);
    }

    public static Path writeString(final Path path, final CharSequence csq, final Charset cs,
                                   final OpenOption... options) throws IOException {
        return write(path, csq.toString().getBytes(cs), options);
    }

    public static BufferedWriter newBufferedWriter(final Path path, final OpenOption... options) throws IOException {
        return newBufferedWriter(path, StandardCharsets.UTF_8, options);
    }

    public static BufferedWriter newBufferedWriter(final Path path, final Charset cs, final OpenOption... options)
            throws IOException {
        return new BufferedWriter(new OutputStreamWriter(newOutputStream(path, options), cs));
    }

    public static Path write(final Path path, final Iterable<? extends CharSequence> lines, final Charset cs,
                             final OpenOption... options) throws IOException {
        final BufferedWriter writer = newBufferedWriter(path, cs, options);
        try {
            for (final CharSequence line : lines) {
                writer.append(line);
                writer.newLine();
            }
        } finally {
            writer.close();
        }
        return path;
    }

    public static Path write(final Path path, final Iterable<? extends CharSequence> lines,
                             final OpenOption... options) throws IOException {
        return write(path, lines, StandardCharsets.UTF_8, options);
    }

    // ---- creating and deleting

    public static Path createFile(final Path path, final FileAttribute<?>... attrs) throws IOException {
        final File file = path.toFile();
        if (file.exists()) {
            throw new FileAlreadyExistsException(path.toString());
        }
        if (!file.createNewFile()) {
            throw new IOException(path.toString());
        }
        return path;
    }

    public static Path createDirectory(final Path dir, final FileAttribute<?>... attrs) throws IOException {
        final File file = dir.toFile();
        if (file.exists()) {
            throw new FileAlreadyExistsException(dir.toString());
        }
        if (!file.mkdir()) {
            throw new NoSuchFileException(dir.toString());
        }
        return dir;
    }

    public static Path createDirectories(final Path dir, final FileAttribute<?>... attrs) throws IOException {
        final File file = dir.toFile();
        if (file.isDirectory()) {
            return dir;
        }
        if (file.exists()) {
            throw new FileAlreadyExistsException(dir.toString());
        }
        if (!file.mkdirs()) {
            throw new IOException(dir.toString());
        }
        return dir;
    }

    public static void delete(final Path path) throws IOException {
        final File file = path.toFile();
        if (!file.exists()) {
            throw new NoSuchFileException(path.toString());
        }
        if (!file.delete()) {
            if (file.isDirectory()) {
                throw new DirectoryNotEmptyException(path.toString());
            }
            throw new IOException(path.toString());
        }
    }

    public static boolean deleteIfExists(final Path path) throws IOException {
        final File file = path.toFile();
        if (!file.exists()) {
            return false;
        }
        delete(path);
        return true;
    }

    // ---- copying and moving

    private static boolean replaceExisting(final CopyOption[] options) {
        for (final CopyOption option : options) {
            if (option == StandardCopyOption.REPLACE_EXISTING) {
                return true;
            }
        }
        return false;
    }

    public static Path copy(final Path source, final Path target, final CopyOption... options) throws IOException {
        final File to = target.toFile();
        if (to.exists() && !replaceExisting(options)) {
            throw new FileAlreadyExistsException(target.toString());
        }
        final InputStream in = newInputStream(source);
        try {
            final OutputStream out = new FileOutputStream(to);
            try {
                in.transferTo(out);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        return target;
    }

    public static Path move(final Path source, final Path target, final CopyOption... options) throws IOException {
        final File from = source.toFile();
        final File to = target.toFile();
        if (!from.exists()) {
            throw new NoSuchFileException(source.toString());
        }
        if (to.exists()) {
            if (!replaceExisting(options)) {
                throw new FileAlreadyExistsException(target.toString());
            }
            to.delete();
        }
        if (!from.renameTo(to)) {
            throw new IOException(source.toString());
        }
        return target;
    }
}
