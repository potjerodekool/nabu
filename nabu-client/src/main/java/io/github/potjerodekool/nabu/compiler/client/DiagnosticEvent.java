package io.github.potjerodekool.nabu.compiler.client;

public record DiagnosticEvent(Kind kind,
                              String fileName,
                              String message) implements DaemonEvent {

    public enum Kind {
        ERROR,
        WARN,
        MANDATORY_WARNING,
        NOTE,
        OTHER
    }
}
