import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.UnaryOperator;

public class Main {
    public static void main(String[] args) throws Exception {
        try (StructuredTaskScope<Integer, java.util.stream.Stream<StructuredTaskScope.Subtask<Integer>>> scope =
                     StructuredTaskScope.open(StructuredTaskScope.Joiner.<Integer>allSuccessfulOrThrow())) {
            for (int i = 0; i < 3; i++) {
                scope.fork(new Callable<Integer>() {
                    public Integer call() {
                        return 1;
                    }
                });
            }
            System.out.println("all " + scope.join().count());
        }
    }
}
