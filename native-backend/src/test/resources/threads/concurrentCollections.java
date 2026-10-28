import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;

public class Main {
    static final ConcurrentLinkedQueue<Integer> queue = new ConcurrentLinkedQueue<Integer>();
    static final ConcurrentLinkedDeque<Integer> deque = new ConcurrentLinkedDeque<Integer>();
    static final ConcurrentSkipListMap<Integer, Integer> skip = new ConcurrentSkipListMap<Integer, Integer>();

    public static void main(String[] args) throws Exception {
        final int threads = 4;
        final int per = 5000;
        Thread[] all = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int base = i * per;
            all[i] = new Thread(new Runnable() {
                public void run() {
                    for (int k = 0; k < per; k++) {
                        queue.add(base + k);
                        deque.addFirst(base + k);
                        skip.put(base + k, k);
                    }
                }
            });
        }
        for (Thread t : all) {
            t.start();
        }
        for (Thread t : all) {
            t.join();
        }
        long queueSum = 0;
        int polled = 0;
        Integer v;
        while ((v = queue.poll()) != null) {
            queueSum += v;
            polled++;
        }
        long dequeSum = 0;
        int dequeCount = 0;
        while ((v = deque.pollLast()) != null) {
            dequeSum += v;
            dequeCount++;
        }
        long skipSum = 0;
        for (int key : skip.keySet()) {
            skipSum += key;
        }
        System.out.println("queue " + polled + " " + queueSum);
        System.out.println("deque " + dequeCount + " " + dequeSum);
        System.out.println("skip " + skip.size() + " " + skipSum);
    }
}
