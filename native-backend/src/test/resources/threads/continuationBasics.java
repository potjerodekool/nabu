import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;

public class Main {
    static final ContinuationScope SCOPE = new ContinuationScope("test");
    static final StringBuilder log = new StringBuilder();

    static final class Node {
        Node next;
        long value;
        long pad1, pad2, pad3, pad4;
    }

    static long sum(Node n) {
        long s = 0;
        for (; n != null; n = n.next) {
            s += n.value;
        }
        return s;
    }

    public static void main(String[] args) throws Exception {
        basics();
        failure();
        nested();
        stacksAndCollections();
        migration();
    }

    static void basics() {
        Continuation c = new Continuation(SCOPE, new Runnable() {
            public void run() {
                log.append("a");
                Continuation.yield(SCOPE);
                log.append("b");
                Continuation.yield(SCOPE);
                log.append("c");
            }
        });
        int runs = 0;
        while (!c.isDone()) {
            log.append("[");
            c.run();
            log.append("]");
            runs++;
        }
        System.out.println("basics " + log + " runs " + runs + " done " + c.isDone());
    }

    static void failure() {
        Continuation c = new Continuation(SCOPE, new Runnable() {
            public void run() {
                Continuation.yield(SCOPE);
                throw new IllegalStateException("boom");
            }
        });
        c.run();
        try {
            c.run();
            System.out.println("failure none");
        } catch (IllegalStateException e) {
            System.out.println("failure " + e.getMessage() + " " + c.isDone());
        }
    }

    static void nested() {
        final ContinuationScope outerScope = new ContinuationScope("outer");
        final StringBuilder trace = new StringBuilder();
        Continuation outer = new Continuation(outerScope, new Runnable() {
            public void run() {
                trace.append("o1 ");
                Continuation inner = new Continuation(SCOPE, new Runnable() {
                    public void run() {
                        trace.append("i1 ");
                        Continuation.yield(SCOPE);
                        trace.append("i2 ");
                    }
                });
                inner.run();
                trace.append("o2 ");
                Continuation.yield(outerScope);
                inner.run();
                trace.append("o3");
            }
        });
        outer.run();
        trace.append("| ");
        outer.run();
        System.out.println("nested " + trace + " " + outer.isDone());
    }

    /** The data lives only on the stack of a suspended continuation: collections must keep it. */
    static void stacksAndCollections() {
        final int count = 300;
        final long[] sums = new long[count];
        final long[] expected = new long[count];
        Continuation[] all = new Continuation[count];
        for (int i = 0; i < count; i++) {
            final int id = i;
            all[i] = new Continuation(SCOPE, new Runnable() {
                public void run() {
                    Node list = null;
                    for (int k = 0; k < 40; k++) {
                        Node n = new Node();
                        n.value = id * 1000L + k;
                        n.next = list;
                        list = n;
                    }
                    long before = sum(list);
                    for (int round = 0; round < 4; round++) {
                        Continuation.yield(SCOPE);
                        for (int g = 0; g < 50; g++) {
                            Node garbage = new Node();
                            garbage.value = g;
                        }
                    }
                    sums[id] = sum(list) - before;
                }
            });
            long e = 0;
            for (int k = 0; k < 40; k++) {
                e += i * 1000L + k;
            }
            expected[i] = e;
        }
        for (int round = 0; round < 5; round++) {
            for (Continuation c : all) {
                if (!c.isDone()) {
                    c.run();
                }
            }
            byte[][] waste = new byte[200][];
            for (int w = 0; w < waste.length; w++) {
                waste[w] = new byte[20000];
            }
        }
        int done = 0;
        int intact = 0;
        for (int i = 0; i < count; i++) {
            if (all[i].isDone()) {
                done++;
            }
            if (sums[i] == 0) {
                intact++;
            }
        }
        System.out.println("stacks done " + done + " intact " + intact);
    }

    /** Started on one thread, finished on another. */
    static void migration() throws Exception {
        final long[] result = new long[1];
        final Continuation c = new Continuation(SCOPE, new Runnable() {
            public void run() {
                Node list = null;
                for (int k = 0; k < 10; k++) {
                    Node n = new Node();
                    n.value = k;
                    n.next = list;
                    list = n;
                }
                Continuation.yield(SCOPE);
                result[0] = sum(list);
            }
        });
        Thread first = new Thread(new Runnable() {
            public void run() {
                c.run();
            }
        });
        first.start();
        first.join();
        Thread second = new Thread(new Runnable() {
            public void run() {
                c.run();
            }
        });
        second.start();
        second.join();
        System.out.println("migration " + c.isDone() + " " + result[0]);
    }
}
