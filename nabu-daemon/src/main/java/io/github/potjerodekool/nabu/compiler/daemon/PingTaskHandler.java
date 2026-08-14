package io.github.potjerodekool.nabu.compiler.daemon;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;

public class PingTaskHandler implements TaskHandler {

    @Override
    public void handle(final DataInputStream in, final DataOutputStream out) throws IOException {
        final var message = "PONG " + new Date();
        final byte[] data = message.getBytes(StandardCharsets.UTF_8);
        out.writeByte(Protocol.STATUS_SUCCESS);
        out.writeInt(data.length);
        out.write(data);
        out.flush();
    }
}
