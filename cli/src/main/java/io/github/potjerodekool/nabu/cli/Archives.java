package io.github.potjerodekool.nabu.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Unpacking release archives and checking them against {@code SHA256SUMS}. */
final class Archives {

    private Archives() {
    }

    /**
     * Unpacks a zip file into a directory. An entry that would end up outside the directory ({@code ../x}) is an
     * error: nothing is written for it.
     */
    static void extract(final Path zip, final Path directory) throws IOException {
        final Path root = directory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        try (InputStream in = Files.newInputStream(zip); ZipInputStream zipStream = new ZipInputStream(in)) {
            for (ZipEntry entry = zipStream.getNextEntry(); entry != null; entry = zipStream.getNextEntry()) {
                final Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("the archive has an entry outside its directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zipStream, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** The lines {@code <sha256>  <file name>} of a {@code SHA256SUMS} file. */
    static Map<String, String> parseSums(final String text) {
        final Map<String, String> sums = new HashMap<>();
        for (final String line : text.split("\\R")) {
            final String trimmed = line.trim();
            final int space = trimmed.indexOf(' ');
            if (space > 0) {
                String name = trimmed.substring(space).trim();
                if (name.startsWith("*")) {
                    name = name.substring(1);
                }
                sums.put(name, trimmed.substring(0, space).toLowerCase());
            }
        }
        return sums;
    }

    static String sha256(final Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] buffer = new byte[1 << 16];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (final NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    static String read(final Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
