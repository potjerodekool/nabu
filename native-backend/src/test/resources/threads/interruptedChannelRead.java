import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A thread that is blocked in a read of a channel is woken by Thread.interrupt: the interrupt closes the channel, the
 * read fails with ClosedByInterruptException and the interrupt status stays set.
 */
public class Main {
    public static void main(String[] args) throws Exception {
        final Path dir = Files.createTempDirectory("nabu-fifo");
        final Path fifo = dir.resolve("pipe");
        final Process mk = new ProcessBuilder("mkfifo", fifo.toString()).start();
        if (mk.waitFor() != 0) {
            System.out.println("mkfifo failed");
            return;
        }
        // read and write: opening does not wait for another process, and nothing is ever written, so a read blocks
        final FileChannel channel = FileChannel.open(fifo, StandardOpenOption.READ, StandardOpenOption.WRITE);
        final String[] result = new String[2];
        final Thread reader = new Thread(new Runnable() {
            public void run() {
                try {
                    channel.read(ByteBuffer.allocate(16));
                    result[0] = "read returned";
                } catch (ClosedByInterruptException e) {
                    result[0] = "ClosedByInterruptException";
                } catch (java.io.IOException e) {
                    result[0] = e.getClass().getName();
                }
                result[1] = String.valueOf(Thread.currentThread().isInterrupted());
            }
        });
        reader.start();
        Thread.sleep(500);
        System.out.println("blocked " + reader.isAlive());
        reader.interrupt();
        reader.join(10000);
        System.out.println("ended " + !reader.isAlive());
        System.out.println(result[0]);
        System.out.println("interrupted " + result[1]);
        System.out.println("open " + channel.isOpen());
        Files.delete(fifo);
        Files.delete(dir);
    }
}
