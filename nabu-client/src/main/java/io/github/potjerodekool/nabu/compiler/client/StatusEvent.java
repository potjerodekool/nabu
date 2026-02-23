package io.github.potjerodekool.nabu.compiler.client;

public record StatusEvent(Status status, String message) implements DaemonEvent {

}
