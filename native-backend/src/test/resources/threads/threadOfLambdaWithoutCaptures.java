public class Main {
    public static void main(String[] args) throws Exception {
        Thread worker = new Thread(() -> System.out.println("lambda thread ran"));
        worker.start();
        worker.join();
    }
}
