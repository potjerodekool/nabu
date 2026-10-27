import java.util.concurrent.Callable;
import java.util.concurrent.StructuredTaskScope;

public class Main {
    public static void main(String[] args) throws Exception {
        try (StructuredTaskScope<Object, Void> scope = StructuredTaskScope.open()) {
            StructuredTaskScope.Subtask<Integer> a = scope.fork(new Callable<Integer>() {
                public Integer call() {
                    return 30;
                }
            });
            StructuredTaskScope.Subtask<Integer> b = scope.fork(new Callable<Integer>() {
                public Integer call() {
                    return 12;
                }
            });
            scope.join();
            System.out.println(a.get() + " " + b.get());
        }
    }
}
