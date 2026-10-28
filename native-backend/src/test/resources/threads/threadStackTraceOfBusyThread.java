public class Main {
    static volatile boolean stop;
    static long sink;

    static void crunch() {
        long x = 1;
        while (!stop) {
            for (int i = 0; i < 1000; i++) {
                x = x * 31 + i;
            }
            sink = x;
        }
    }

    public static void main(String[] args) throws Exception {
        Thread busy = new Thread(new Runnable() {
            public void run() {
                crunch();
            }
        });
        busy.start();
        Thread.sleep(100);
        boolean found = false;
        for (int round = 0; round < 3; round++) {
            StackTraceElement[] trace = busy.getStackTrace();
            for (StackTraceElement e : trace) {
                if (e.getMethodName().equals("crunch") || e.getMethodName().equals("run")) {
                    found = true;
                }
            }
        }
        System.out.println("busy " + found);
        stop = true;
        busy.join();
        System.out.println("done");
    }
}
