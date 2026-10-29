import java.util.concurrent.locks.LockSupport;

public class Main {
    public static void main(String[] args) throws Exception {
        ownTrace();
        exceptionTrace();
        parkedTrace();
    }

    /** Inlining may merge frames, so only tell whether the innermost frame and the virtual thread's own frame are there. */
    static String names(StackTraceElement[] trace) {
        boolean inner = false;
        boolean vthread = false;
        for (StackTraceElement e : trace) {
            if (e.getMethodName().equals("level3")) inner = true;
            if (e.getClassName().equals("java.lang.VirtualThread") && e.getMethodName().equals("run")) vthread = true;
        }
        return "inner " + inner + " vthread " + vthread;
    }

    static void level1(StackTraceElement[][] out) { level2(out); }
    static void level2(StackTraceElement[][] out) { level3(out); }
    static void level3(StackTraceElement[][] out) { out[0] = new Throwable().getStackTrace(); }

    /** A trace taken inside a virtual thread shows the frames of the virtual thread. */
    static void ownTrace() throws Exception {
        final StackTraceElement[][] out = new StackTraceElement[1][];
        Thread t = Thread.ofVirtual().start(new Runnable() {
            public void run() { level1(out); }
        });
        t.join();
        System.out.println("own " + names(out[0]));
    }

    static void exceptionTrace() throws Exception {
        final String[] out = new String[1];
        Thread t = Thread.ofVirtual().start(new Runnable() {
            public void run() {
                try {
                    level1(null);
                } catch (NullPointerException e) {
                    out[0] = names(e.getStackTrace());
                }
            }
        });
        t.join();
        System.out.println("npe " + out[0]);
    }

    /** The trace of a parked virtual thread, asked by another thread. */
    static void parkedTrace() throws Exception {
        final boolean[] go = new boolean[1];
        Thread t = Thread.ofVirtual().start(new Runnable() {
            public void run() { parkedWork(go); }
        });
        Thread.sleep(200);
        StackTraceElement[] parkedTrace = t.getStackTrace();
        boolean work = false;
        boolean park = false;
        boolean hidden = false;
        for (StackTraceElement e : parkedTrace) {
            if (e.getMethodName().equals("parkedWork")) work = true;
            if (e.getClassName().equals("java.lang.VirtualThread") && e.getMethodName().equals("park")) park = true;
            if (e.getClassName().startsWith("jdk.internal.vm.Continuation")) hidden = true;
        }
        go[0] = true;
        LockSupport.unpark(t);
        t.join();
        System.out.println("parked work " + work + " park " + park + " hidden " + hidden);
    }

    static void parkedWork(boolean[] go) {
        while (!go[0]) {
            LockSupport.park();
        }
    }
}
