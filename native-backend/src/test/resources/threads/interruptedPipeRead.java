import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;

/**
 * A thread that is blocked in a read of a channel is woken by Thread.interrupt: the interrupt closes the channel, the
 * read fails with ClosedByInterruptException and the interrupt status stays set. The channel is standard input, a pipe
 * that nothing writes to; a platform thread and a virtual thread (pinned in the read) are both interrupted.
 */
public class Main {
    static void run(boolean virtual) throws Exception {
        final FileChannel channel = new FileInputStream(FileDescriptor.in).getChannel();
        final String[] result = new String[2];
        final Runnable body = new Runnable() {
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
        };
        final Thread reader = virtual ? Thread.ofVirtual().unstarted(body) : new Thread(body);
        reader.start();
        Thread.sleep(500);
        System.out.println((virtual ? "virtual" : "platform") + " blocked " + reader.isAlive());
        reader.interrupt();
        reader.join(10000);
        System.out.println("ended " + !reader.isAlive());
        System.out.println(result[0]);
        System.out.println("interrupted " + result[1]);
        System.out.println("open " + channel.isOpen());
    }

    public static void main(String[] args) throws Exception {
        run(false);
    }
}
