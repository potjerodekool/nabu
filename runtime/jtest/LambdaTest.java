import java.util.function.IntBinaryOperator;
public class LambdaTest {
    public static void main(String[] args) {
        IntBinaryOperator add = (a, b) -> a + b;
        int k = 10;
        IntBinaryOperator addK = (a, b) -> a + b + k;
        System.out.println(add.applyAsInt(3, 4));   // 7
        System.out.println(addK.applyAsInt(3, 4));  // 17
    }
}
