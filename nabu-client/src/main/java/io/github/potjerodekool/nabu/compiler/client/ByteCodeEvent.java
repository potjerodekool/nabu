package io.github.potjerodekool.nabu.compiler.client;

public record ByteCodeEvent(String sourceFileName,
                            String classFileName,
                            String className) implements DaemonEvent {

}
