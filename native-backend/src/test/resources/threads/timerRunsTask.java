public class Main {
    public static void main(String[] args) throws Exception {
        java.util.Timer timer = new java.util.Timer();
        timer.schedule(new java.util.TimerTask() {
            public void run() {
                System.out.println("tick");
            }
        }, 50);
        Thread.sleep(300);
        timer.cancel();
        System.out.println("end");
    }
}
