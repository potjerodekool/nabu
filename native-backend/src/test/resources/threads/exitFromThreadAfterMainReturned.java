public class Main {
    public static void main(String[] args) throws Exception {
        Thread worker = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                System.out.println("worker exits");
                System.exit(3);
            }
        });
        worker.start();
        System.out.println("main returns");
    }
}
