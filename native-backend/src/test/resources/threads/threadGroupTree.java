public class Main {
    public static void main(String[] args) throws Exception {
        ThreadGroup main = Thread.currentThread().getThreadGroup();
        System.out.println(main.getName() + " " + main.getParent().getName());
        ThreadGroup workers = new ThreadGroup(main, "workers");
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
        }, "worker");
        t.start();
        Thread.sleep(50);
        System.out.println(t.getName() + " group " + t.getThreadGroup().getName());
        System.out.println("parent " + workers.getParent().getName());
        System.out.println("active " + main.activeCount() + (workers.activeCount() == 1 ? "" : " wrong"));
        System.out.println("groups " + main.activeGroupCount());
        t.interrupt();
        t.join();
        System.out.println("ended " + t.getThreadGroup());
    }
}
