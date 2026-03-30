package io.github.potjerodekool.nabu.compiler.client;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

@FunctionalInterface
public interface ActionWithResult<R> {

    R apply(DataInputStream inputStream,
            DataOutputStream outputStream) throws IOException;
}
