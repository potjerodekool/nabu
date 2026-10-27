import java.util.Map;

public class Main {
    static final Object lock = new Object();
    static final Object contended = new Object();

    static boolean has(StackTraceElement[] trace, String method) {
        for (StackTraceElement e : trace) {
            if (e.getMethodName().equals(method)) {
                return true;
            }
        }
        return false;
    }

    static void sleepingLeaf() throws InterruptedException {
        Thread.sleep(20000);
    }

    static void waitingLeaf() throws InterruptedException {
        synchronized (lock) {
            lock.wait();
        }
    }

    static void blockedLeaf() {
        synchronized (contended) {
        }
    }

    static void waitFor(Thread t, Thread.State state) throws Exception {
        for (int i = 0; i < 5000 && t.getState() != state; i++) {
            Thread.sleep(1);
        }
    }

    public static void main(String[] args) throws Exception {
        Thread sleeper = new Thread(new Runnable() {
            public void run() {
                try {
                    sleepingLeaf();
                } catch (InterruptedException e) {
                }
            }
        });
        Thread waiter = new Thread(new Runnable() {
            public void run() {
                try {
                    waitingLeaf();
                } catch (InterruptedException e) {
                }
            }
        });
        Thread blocked = new Thread(new Runnable() {
            public void run() {
                blockedLeaf();
            }
        });
        System.out.println("new " + sleeper.getStackTrace().length);

        sleeper.start();
        waiter.start();
        waitFor(sleeper, Thread.State.TIMED_WAITING);
        waitFor(waiter, Thread.State.WAITING);
        StackTraceElement[] sleeping = sleeper.getStackTrace();
        // the frames of methods that LLVM inlined are missing: only look for the natives that cannot be inlined
        System.out.println("sleeper " + (sleeping.length > 0) + " " + has(sleeping, "sleep"));
        StackTraceElement[] waiting = waiter.getStackTrace();
        System.out.println("waiter " + (waiting.length > 0) + " " + (has(waiting, "wait") || has(waiting, "wait0")));

        synchronized (contended) {
            blocked.start();
            waitFor(blocked, Thread.State.BLOCKED);
            StackTraceElement[] stuck = blocked.getStackTrace();
            System.out.println("blocked " + (stuck.length > 0));
        }
        blocked.join();

        StackTraceElement[] own = Thread.currentThread().getStackTrace();
        System.out.println("own " + has(own, "getStackTrace"));

        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        System.out.println("all " + all.containsKey(sleeper) + " " + (all.get(sleeper).length > 0));

        sleeper.interrupt();
        synchronized (lock) {
            lock.notifyAll();
        }
        sleeper.join();
        waiter.join();
        System.out.println("ended " + sleeper.getStackTrace().length);
    }
}
