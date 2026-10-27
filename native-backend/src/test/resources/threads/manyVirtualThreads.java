import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    public static void main(String[] args) throws Exception {
        final String requested = System.getenv("VT_COUNT");
        final int count = requested == null ? 2000 : Integer.parseInt(requested);
        final String pauseText = System.getenv("VT_SLEEP");
        final int pause = pauseText == null ? 50 : Integer.parseInt(pauseText);
        final AtomicInteger done = new AtomicInteger();
        Thread[] all = new Thread[count];
        for (int i = 0; i < count; i++) {
            all[i] = Thread.ofVirtual().start(new Runnable() {
                public void run() {
                    try {
                        Thread.sleep(pause);
                    } catch (InterruptedException e) {
                    }
                    done.incrementAndGet();
                }
            });
        }
        for (Thread t : all) {
            t.join();
        }
        System.out.println("done " + done.get());
    }
}
