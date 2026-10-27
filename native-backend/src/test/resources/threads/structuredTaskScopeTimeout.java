import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.UnaryOperator;

public class Main {
    public static void main(String[] args) throws Exception {
        UnaryOperator<StructuredTaskScope.Configuration> config = new UnaryOperator<StructuredTaskScope.Configuration>() {
            public StructuredTaskScope.Configuration apply(StructuredTaskScope.Configuration cf) {
                return cf.withTimeout(Duration.ofMillis(100));
            }
        };
        try (StructuredTaskScope<Integer, Void> scope =
                     StructuredTaskScope.open(StructuredTaskScope.Joiner.<Integer>awaitAllSuccessfulOrThrow(), config)) {
            scope.fork(new Callable<Integer>() {
                public Integer call() throws Exception {
                    Thread.sleep(10000);
                    return 1;
                }
            });
            scope.join();
            System.out.println("timeout false");
        } catch (RuntimeException e) {
            System.out.println("timeout " + (e instanceof StructuredTaskScope.TimeoutException));
        }
    }
}
