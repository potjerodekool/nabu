public class Main {
    static volatile boolean ready;

    public static void main(String[] args) throws Exception {
        Thread watcher = new Thread(new Runnable() {
            public void run() {
                while (!ready) {
                }
                System.out.println("saw ready");
            }
        });
        watcher.start();
        Thread.sleep(50);
        ready = true;
        watcher.join();
        Thread looper = new Thread(new Runnable() {
            public void run() {
                while (!Thread.currentThread().isInterrupted()) {
                }
                System.out.println("interrupt seen");
            }
        });
        looper.start();
        Thread.sleep(50);
        looper.interrupt();
        looper.join();
    }
}
