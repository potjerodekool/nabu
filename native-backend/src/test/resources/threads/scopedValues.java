public class Main {
    static final ScopedValue<String> USER = ScopedValue.newInstance();

    public static void main(String[] args) throws Exception {
        System.out.println("outside " + USER.isBound());
        ScopedValue.where(USER, "alice").run(new Runnable() {
            public void run() {
                System.out.println("inside " + USER.get());
                ScopedValue.where(USER, "bob").run(new Runnable() {
                    public void run() {
                        System.out.println("nested " + USER.get());
                    }
                });
                System.out.println("back " + USER.get());
            }
        });
        String r = ScopedValue.where(USER, "carol").call(new ScopedValue.CallableOp<String, RuntimeException>() {
            public String call() {
                return USER.get() + "!";
            }
        });
        System.out.println("call " + r);
        System.out.println("after " + USER.isBound() + " " + USER.orElse("none"));
        Thread v = Thread.ofVirtual().unstarted(new Runnable() {
            public void run() {
                System.out.println("virtual " + USER.isBound());
            }
        });
        v.start();
        v.join();
    }
}
