public class Main {
    static long countPrimes(int from, int to) {
        long count = 0;
        for (int n = from; n < to; n++) {
            if (n < 2) {
                continue;
            }
            boolean prime = true;
            for (int d = 2; (long) d * d <= n; d++) {
                if (n % d == 0) {
                    prime = false;
                    break;
                }
            }
            if (prime) {
                count++;
            }
        }
        return count;
    }

    public static void main(String[] args) throws Exception {
        final int threads = 4;
        final int span = 800000;
        final long[] counts = new long[threads];
        Thread[] all = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int id = i;
            all[i] = new Thread(new Runnable() {
                public void run() {
                    counts[id] = countPrimes(id * span, (id + 1) * span);
                }
            });
            all[i].start();
        }
        for (Thread t : all) {
            t.join();
        }
        long total = 0;
        for (long c : counts) {
            total += c;
        }
        System.out.println("primes " + total);
    }
}
