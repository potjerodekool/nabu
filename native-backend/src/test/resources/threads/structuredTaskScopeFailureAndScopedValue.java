import java.util.concurrent.Callable;
import java.util.concurrent.StructuredTaskScope;

public class Main {
    static final ScopedValue<String> USER = ScopedValue.newInstance();

    public static void main(String[] args) throws Exception {
        ScopedValue.where(USER, "alice").run(new Runnable() {
            public void run() {
                try (StructuredTaskScope<Object, Void> scope = StructuredTaskScope.open()) {
                    StructuredTaskScope.Subtask<String> a = scope.fork(new Callable<String>() {
                        public String call() {
                            return "inherited " + USER.get();
                        }
                    });
                    scope.join();
                    System.out.println(a.get());
                } catch (Exception e) {
                    System.out.println("error " + e);
                }
            }
        });
        try (StructuredTaskScope<Object, Void> scope = StructuredTaskScope.open()) {
            StructuredTaskScope.Subtask<String> f = scope.fork(new Callable<String>() {
                public String call() {
                    throw new IllegalStateException("boom");
                }
            });
            scope.join();
            System.out.println("not reached");
        } catch (RuntimeException e) {
            System.out.println("failed " + e.getCause());
        }
    }
}
