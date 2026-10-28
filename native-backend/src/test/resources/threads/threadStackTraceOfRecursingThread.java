public class Main {
    static volatile int result;

    static int fib(int n) {
        return n < 2 ? n : fib(n - 1) + fib(n - 2);
    }

    public static void main(String[] args) throws Exception {
        Thread busy = new Thread(new Runnable() {
            public void run() {
                result = fib(42);
            }
        });
        busy.start();
        Thread.sleep(100);
        boolean found = false;
        for (int round = 0; round < 3; round++) {
            for (StackTraceElement e : busy.getStackTrace()) {
                if (e.getMethodName().equals("fib")) {
                    found = true;
                }
            }
        }
        System.out.println("recursing " + found);
        busy.join();
        System.out.println("fib " + result);
    }
}
