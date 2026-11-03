package io.github.potjerodekool.nabu.compiler.daemon;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class PingTaskHandler implements TaskHandler {

    @Override
    public void handle(final DataInputStream in, final DataOutputStream out) throws IOException {
        // The identity is set by whoever started the daemon (the Maven plugin passes the jar, its time and size).
        // A client that expects another identity restarts the daemon, so it never compiles with an old compiler.
        final var message = "PONG " + System.getProperty("nabu.daemon.id", "unknown");
        final byte[] data = message.getBytes(StandardCharsets.UTF_8);
        out.writeByte(Protocol.STATUS_SUCCESS);
        out.writeInt(data.length);
        out.write(data);
        out.flush();
    }
}
