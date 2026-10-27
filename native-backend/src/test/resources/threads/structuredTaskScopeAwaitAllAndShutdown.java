import java.util.concurrent.Callable;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main {
    public static void main(String[] args) throws Exception {
        try (StructuredTaskScope<Integer, Void> scope =
                     StructuredTaskScope.open(StructuredTaskScope.Joiner.<Integer>awaitAll())) {
            StructuredTaskScope.Subtask<Integer> a = scope.fork(new Callable<Integer>() {
                public Integer call() {
                    return 1;
                }
            });
            StructuredTaskScope.Subtask<Integer> b = scope.fork(new Callable<Integer>() {
                public Integer call() {
                    return 2;
                }
            });
            scope.join();
            System.out.println("states " + a.state() + " " + b.state());
        }

        final AtomicBoolean interrupted = new AtomicBoolean();
        try (StructuredTaskScope<Integer, Integer> scope =
                     StructuredTaskScope.open(StructuredTaskScope.Joiner.<Integer>anySuccessfulResultOrThrow())) {
            scope.fork(new Callable<Integer>() {
                public Integer call() throws Exception {
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e) {
                        interrupted.set(true);
                    }
                    return 0;
                }
            });
            scope.fork(new Callable<Integer>() {
                public Integer call() {
                    return 7;
                }
            });
            scope.join();
        }
        System.out.println("interrupted " + interrupted.get());
    }
}
