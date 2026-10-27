import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.SynchronousQueue;

public class Main {
    public static void main(String[] args) throws Exception {
        final ArrayBlockingQueue<Integer> queue = new ArrayBlockingQueue<Integer>(4);
        Thread producer = new Thread(new Runnable() {
            public void run() {
                try {
                    for (int i = 0; i < 100; i++) {
                        queue.put(i);
                    }
                    queue.put(-1);
                } catch (InterruptedException e) {
                }
            }
        });
        producer.start();
        int sum = 0;
        while (true) {
            int v = queue.take();
            if (v < 0) {
                break;
            }
            sum += v;
        }
        System.out.println("sum " + sum);

        final SynchronousQueue<Integer> sync = new SynchronousQueue<Integer>();
        new Thread(new Runnable() {
            public void run() {
                try {
                    sync.put(7);
                } catch (InterruptedException e) {
                }
            }
        }).start();
        System.out.println("sync " + sync.take());

        PriorityBlockingQueue<Integer> pq = new PriorityBlockingQueue<Integer>();
        pq.add(3);
        pq.add(1);
        pq.add(2);
        int first = pq.take();
        int second = pq.take();
        int third = pq.take();
        System.out.println("priority " + first + " " + second + " " + third);
    }
}
