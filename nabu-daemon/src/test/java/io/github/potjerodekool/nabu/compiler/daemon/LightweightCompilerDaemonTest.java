package io.github.potjerodekool.nabu.compiler.daemon;

import org.junit.jupiter.api.*;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Disabled
class LightweightCompilerDaemonTest {

    private static final String HOST = "localhost";
    private static final int PORT = 9876;
    private static final int TIMEOUT = 5000; // 5 seconden

    // Protocol constants
    private static final byte CMD_PING = 0x02;
    private static final byte STATUS_SUCCESS = 0x30;

    private static Thread daemonThread;
    private static LightweightCompilerDaemon daemon;
//    private static final LightweightClient client = new LightweightClient();

    @BeforeAll
    static void startDaemon() throws Exception {
        // Start daemon in aparte thread
        daemon = new LightweightCompilerDaemon();
        daemonThread = new Thread(() -> {
            try {
                //daemon.initialize();
                daemon.start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        daemonThread.setDaemon(true);
        daemonThread.start();

        // Wacht tot daemon klaar is
        Thread.sleep(2000);

        // Verify daemon is running
        assertTrue(isDaemonRunning(), "Daemon moet draaien voor tests");
    }

    static boolean isDaemonRunning() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @AfterAll
    static void stopDaemon() throws Exception {
        if (daemon != null) {
            daemon.stop();
        }
        if (daemonThread != null) {
            daemonThread.interrupt();
        }

        //client.shutdown();
    }

    @Test
    @DisplayName("PING command moet SUCCESS response geven")
    void testPingSuccess() throws IOException {
        try (Socket socket = new Socket(HOST, PORT);
             DataOutputStream out = new DataOutputStream(socket.getOutputStream());
             DataInputStream in = new DataInputStream(socket.getInputStream())) {

            // Stuur PING
            out.writeByte(CMD_PING);
            out.flush();

            // Lees response
            byte status = in.readByte();
            int length = in.readInt();
            byte[] data = new byte[length];
            in.readFully(data);

            String response = new String(data, StandardCharsets.UTF_8);

            // Assertions
            assertEquals(STATUS_SUCCESS, status, "Status moet SUCCESS zijn");
            assertTrue(length > 0, "Response moet data bevatten");
            assertTrue(response.startsWith("PONG"), "Response moet beginnen met PONG");
            assertTrue(response.contains(" "), "Response moet timestamp bevatten");
        }
    }

    @Test
    @DisplayName("Compile code")
    void testCompileSuccess() {
        /*
        final var messages = new ArrayList<String>();

        client.compile(
                Map.of(
                        "--source-path", "../compiler-test/src/main/nabu",
                        "--class-path", "classes",
                        "-d ", "output"
                ),
                messages::add
        );

        messages.forEach(System.out::println);
        */
    }


}