package io.github.potjerodekool.nabu.compiler.daemon.launcher;

public class ArtifactFetchException extends RuntimeException {

    public ArtifactFetchException(final String message) {
        super(message);
    }

    public ArtifactFetchException(final String message, final Throwable cause) {
        super(message, cause);
    }

}
