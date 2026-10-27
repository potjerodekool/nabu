public class Main {
    static class Worker extends Thread {
        private int value;

        Worker(String name, int value) {
            super(name);
            this.value = value;
        }

        Worker(int value) {
            this.value = value;
        }

        public void run() {
            System.out.println(getName() + " running");
        }
    }

    public static void main(String[] args) throws Exception {
        Worker named = new Worker("worker", 1);
        named.start();
        named.join();
        Worker plain = new Worker(2);
        System.out.println(plain.getName() + " " + (plain.getState() == Thread.State.NEW));
    }
}
