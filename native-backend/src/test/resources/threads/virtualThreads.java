import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    public static void main(String[] args) throws Exception {
        Thread v = Thread.ofVirtual().name("v1").unstarted(new Runnable() {
            public void run() {
                System.out.println("hello from " + Thread.currentThread().getName());
            }
        });
        System.out.println("virtual " + v.isVirtual() + " daemon " + v.isDaemon());
        System.out.println("name " + v.getName());
        v.start();
        v.join();
        final AtomicInteger count = new AtomicInteger();
        Thread[] threads = new Thread[6];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = Thread.startVirtualThread(new Runnable() {
                public void run() {
                    count.incrementAndGet();
                }
            });
        }
        for (Thread t : threads) {
            t.join();
        }
        System.out.println("started " + count.get());
    }
}
