package io.github.potjerodekool.nabu.compiler.client;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

@FunctionalInterface
public interface Action {

    void apply(DataInputStream inputStream,
               DataOutputStream outputStream) throws IOException;
}
