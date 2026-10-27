import java.util.concurrent.SynchronousQueue;

public class Main {
    static final Object lock = new Object();
    static boolean ping = true;

    public static void main(String[] args) throws Exception {
        final int rounds = 2000;
        final SynchronousQueue<Integer> queue = new SynchronousQueue<Integer>();
        Thread consumer = new Thread(new Runnable() {
            public void run() {
                try {
                    for (int i = 0; i < rounds; i++) {
                        queue.take();
                    }
                } catch (InterruptedException e) {
                }
            }
        });
        consumer.start();
        for (int i = 0; i < rounds; i++) {
            queue.put(i);
        }
        consumer.join();
        System.out.println("queue " + rounds);

        Thread other = new Thread(new Runnable() {
            public void run() {
                try {
                    for (int i = 0; i < rounds; i++) {
                        synchronized (lock) {
                            while (ping) {
                                lock.wait();
                            }
                            ping = true;
                            lock.notifyAll();
                        }
                    }
                } catch (InterruptedException e) {
                }
            }
        });
        other.start();
        for (int i = 0; i < rounds; i++) {
            synchronized (lock) {
                ping = false;
                lock.notifyAll();
                while (!ping) {
                    lock.wait();
                }
            }
        }
        other.join();
        System.out.println("monitor " + rounds);
    }
}
