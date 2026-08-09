package io.github.potjerodekool.nabu.compiler.daemon;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public interface TaskHandler {
    void handle(DataInputStream in,
                DataOutputStream out) throws IOException;
}
