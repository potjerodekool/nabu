package io.github.potjerodekool.nabu.compiler;

import org.junit.jupiter.api.Test;

import java.io.File;

public class TT {

    @Test
    public void t() {
        final var root = new File("C:\\Users\\evert\\Downloads");
        final var files = root.listFiles();
        final var MB = 1024 * 1024;

        for (final var file : files) {
            final var length = length(file);
            final var MBSize = length / MB;

            if (MBSize > 100) {
                System.out.println(file.getAbsolutePath() + " " + MBSize + " MB");
            }
        }
    }

    @Test
    public void cleanup() {
        final var root = new File("C:\\projects");
        cleanUp(root);
    }

    private void cleanUp(final File dir) {
        System.out.println("Cleaning up " + dir.getAbsolutePath());

        final var files = dir.listFiles();

        for (final var file : files) {
            if (file.isDirectory()) {
                if (file.getName().equals("node_modules")) {
                    delete(file);
                } else {
                    cleanUp(file);
                }
            }
        }
    }

    private void delete(final File file) {
        if (file.isDirectory()) {
            for (final var child : file.listFiles()) {
                delete(child);
            }
            file.delete();
        } else {
            file.delete();
        }
    }

    private long length(final File file) {
        if (file.isDirectory()) {
            var length = 0L;

            for (final var listFile : file.listFiles()) {
                length += length(listFile);
            }

            return length;
        }

        return file.length();
    }
}
