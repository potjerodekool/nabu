public class Main {
    static volatile boolean tInterrupted;
    static volatile boolean uInterrupted;

    public static void main(String[] args) throws Exception {
        ThreadGroup g = new ThreadGroup("g");
        ThreadGroup sub = new ThreadGroup(g, "sub");
        Thread t = new Thread(g, new Runnable() {
            public void run() {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    tInterrupted = true;
                }
            }
        }, "t");
        Thread u = new Thread(sub, new Runnable() {
            public void run() {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    uInterrupted = true;
                }
            }
        }, "u");
        t.start();
        u.start();
        Thread.sleep(100);
        System.out.println("active " + g.activeCount() + " groups " + g.activeGroupCount());
        g.interrupt();
        t.join();
        u.join();
        System.out.println("interrupted " + tInterrupted + " " + uInterrupted);
        System.out.println("destroyed " + g.isDestroyed() + " daemon " + g.isDaemon() + " maxpri " + g.getMaxPriority());
        g.setMaxPriority(3);
        System.out.println("sub maxpri " + sub.getMaxPriority());
        System.out.println(g);
        System.out.println(g.parentOf(sub) + " " + sub.parentOf(g));
        g.destroy();
        System.out.println("done");
    }
}
