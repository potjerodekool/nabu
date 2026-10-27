public class Main {
    static final Object lock = new Object();
    static int value = 0;
    static int count = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("main " + Thread.currentThread().getName());
        Thread t = new Thread(new Runnable() {
            public void run() {
                System.out.println("child " + Thread.currentThread().getName());
            }
        });
        t.start();
        t.join();
        System.out.println("joined " + t.isAlive());
        System.out.println("join " + t.join(java.time.Duration.ofMillis(10)));
        Thread consumer = new Thread(new Runnable() {
            public void run() {
                synchronized (lock) {
                    while (value == 0) {
                        try {
                            lock.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    System.out.println("got " + value);
                }
            }
        });
        consumer.start();
        Thread.sleep(50);
        synchronized (lock) {
            value = 42;
            lock.notifyAll();
        }
        consumer.join();
        Thread sleeper = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(10000);
                    System.out.println("not interrupted");
                } catch (InterruptedException e) {
                    System.out.println("interrupted " + Thread.currentThread().isInterrupted());
                }
            }
        });
        sleeper.start();
        Thread.sleep(50);
        sleeper.interrupt();
        sleeper.join();
        Thread[] many = new Thread[4];
        for (int i = 0; i < many.length; i++) {
            many[i] = new Thread(new Runnable() {
                public void run() {
                    for (int k = 0; k < 1000; k++) {
                        synchronized (lock) {
                            count++;
                        }
                        if (k % 100 == 0) {
                            Thread.yield();
                        }
                    }
                }
            });
            many[i].start();
        }
        for (int i = 0; i < many.length; i++) {
            many[i].join();
        }
        System.out.println("count " + count);
        new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                }
                System.out.println("late");
            }
        }).start();
        System.out.println("main done");
    }
}
