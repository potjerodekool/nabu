import java.util.function.Function;
import java.util.function.UnaryOperator;

public class Main {
    public static void main(String[] args) throws Exception {
        UnaryOperator<String> op = new UnaryOperator<String>() {
            public String apply(String s) {
                return s + "!";
            }
        };
        Function<String, String> f = op;
        System.out.println(f.apply("a"));
        String mapped = java.util.Optional.of("b").map(op).get();
        System.out.println(mapped);
    }
}
