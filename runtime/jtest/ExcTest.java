public class ExcTest {
    static int div(int a, int b) { return a / b; }
    static int thrower() { throw new RuntimeException("boom"); }
    public static void main(String[] args) {
        try { System.out.println(div(10, 2)); } catch (ArithmeticException e) { System.out.println(-1); }
        try { System.out.println(div(1, 0)); } catch (ArithmeticException e) { System.out.println(-1); }
        try { thrower(); } catch (RuntimeException e) { System.out.println(42); }
        try { System.out.println(7); } finally { System.out.println(8); }
    }
}
