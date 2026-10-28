import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    static final AtomicInteger failures = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        positionalReads();
        concurrentSpawns();
        concurrentClose();
        System.out.println("failures " + failures.get());
    }

    /** FileChannel.read(dst, position) from several threads: every read sees its own bytes, the position stays. */
    static void positionalReads() throws Exception {
        final Path file = Files.createTempFile("nabu", ".bin");
        final byte[] content = new byte[64 * 1024];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 31 + (i >> 8));
        }
        Files.write(file, content);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.position(100);
            Thread[] threads = new Thread[6];
            for (int t = 0; t < threads.length; t++) {
                final int seed = t;
                threads[t] = new Thread(new Runnable() {
                    public void run() {
                        try {
                            ByteBuffer buffer = ByteBuffer.allocate(512);
                            for (int round = 0; round < 400; round++) {
                                int position = ((round * 977 + seed * 4099) % (content.length - 512));
                                buffer.clear();
                                int n = channel.read(buffer, position);
                                if (n != 512) {
                                    failures.incrementAndGet();
                                    continue;
                                }
                                for (int i = 0; i < 512; i++) {
                                    if (buffer.get(i) != content[position + i]) {
                                        failures.incrementAndGet();
                                        break;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            failures.incrementAndGet();
                        }
                    }
                });
                threads[t].start();
            }
            for (Thread t : threads) {
                t.join();
            }
            System.out.println("position " + channel.position());
        }
        Files.delete(file);
    }

    /** Processes started at the same time: each reads its own output up to the end of the stream. */
    static void concurrentSpawns() throws Exception {
        final boolean windows = System.getProperty("os.name").startsWith("Windows");
        final AtomicInteger good = new AtomicInteger();
        Thread[] threads = new Thread[6];
        for (int t = 0; t < threads.length; t++) {
            final int id = t;
            threads[t] = new Thread(new Runnable() {
                public void run() {
                    try {
                        for (int round = 0; round < 5; round++) {
                            String text = "t" + id + "r" + round;
                            Process p = new ProcessBuilder(windows
                                    ? new String[] {"cmd", "/c", "echo " + text}
                                    : new String[] {"sh", "-c", "echo " + text}).start();
                            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                            String line = reader.readLine();
                            String more = reader.readLine();
                            p.waitFor();
                            if (text.equals(line == null ? null : line.trim()) && more == null && p.exitValue() == 0) {
                                good.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    }
                }
            });
            threads[t].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        System.out.println("spawned " + good.get());
    }

    /** Two threads close the same file: one closes it, the other finds it closed. */
    static void concurrentClose() throws Exception {
        final Path file = Files.createTempFile("nabu", ".bin");
        for (int round = 0; round < 50; round++) {
            final RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw");
            Thread[] threads = new Thread[2];
            for (int t = 0; t < threads.length; t++) {
                threads[t] = new Thread(new Runnable() {
                    public void run() {
                        try {
                            raf.close();
                        } catch (Exception e) {
                            failures.incrementAndGet();
                        }
                    }
                });
                threads[t].start();
            }
            for (Thread t : threads) {
                t.join();
            }
        }
        Files.delete(file);
        System.out.println("closed");
    }
}
