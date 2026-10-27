import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) throws Exception {
        final boolean windows = System.getProperty("os.name").startsWith("Windows");
        final Process slow = new ProcessBuilder(windows
                ? new String[] {"cmd", "/c", "ping -n 3 127.0.0.1 > nul"}
                : new String[] {"sleep", "2"}).start();
        long start = System.currentTimeMillis();
        boolean early = slow.waitFor(200, TimeUnit.MILLISECONDS);
        long first = System.currentTimeMillis() - start;
        System.out.println("early " + early + " " + (first >= 150 && first < 1500));

        // two threads wait for the same process
        final boolean[] waited = new boolean[2];
        Thread[] threads = new Thread[2];
        for (int i = 0; i < 2; i++) {
            final int index = i;
            threads[i] = new Thread(new Runnable() {
                public void run() {
                    try {
                        slow.waitFor();
                        waited[index] = true;
                    } catch (InterruptedException e) {
                    }
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        System.out.println("both " + waited[0] + " " + waited[1]);
        System.out.println("exit " + slow.exitValue());

        final Process quick = new ProcessBuilder(windows
                ? new String[] {"cmd", "/c", "exit 3"}
                : new String[] {"sh", "-c", "exit 3"}).start();
        System.out.println("quick " + quick.waitFor(5, TimeUnit.SECONDS) + " " + quick.exitValue());
    }
}
