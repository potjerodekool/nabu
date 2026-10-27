import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.UnaryOperator;

public class Main {
    public static void main(String[] args) throws Exception {
        try (StructuredTaskScope<Integer, Integer> scope =
                     StructuredTaskScope.open(StructuredTaskScope.Joiner.<Integer>anySuccessfulResultOrThrow())) {
            scope.fork(new Callable<Integer>() {
                public Integer call() throws Exception {
                    Thread.sleep(10000);
                    return 1;
                }
            });
            scope.fork(new Callable<Integer>() {
                public Integer call() {
                    return 7;
                }
            });
            System.out.println("any " + scope.join());
        }
    }
}
