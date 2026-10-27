public class Main {
    public static void main(String[] args) throws Exception {
        Thread t1 = new Thread(new Runnable() {
            public void run() {
                throw new IllegalStateException("boom");
            }
        }, "t1");
        t1.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                System.out.println("own " + t.getName() + " " + e.getMessage());
            }
        });
        System.out.println(t1.getUncaughtExceptionHandler() != null ? "get true" : "get false");
        t1.start();
        t1.join();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                System.out.println("default " + t.getName() + " " + e.getMessage());
            }
        });
        Thread t2 = new Thread(new Runnable() {
            public void run() {
                throw new IllegalStateException("bang");
            }
        }, "t2");
        t2.start();
        t2.join();
        System.out.println("after " + t1.getUncaughtExceptionHandler());
    }
}
