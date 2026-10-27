public class Main {
    public static void main(String[] args) throws Exception {
        ThreadGroup workers = new ThreadGroup("workers");
        final Object lock = new Object();
        Thread t = new Thread(workers, new Runnable() {
            public void run() {
                synchronized (lock) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
        }, "listed");
        t.start();
        Thread.sleep(50);
        workers.list();
        t.interrupt();
        t.join();
        System.out.println("ended");
    }
}
