package io.github.potjerodekool.nabu.compiler.client;

public record CheckEvent(Kind kind,
                         String message) implements DaemonEvent {

    enum Kind {
        PING,
        STATUS
    }
}
