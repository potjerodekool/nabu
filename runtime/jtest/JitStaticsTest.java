import java.util.*;

/** getstatic/putstatic of JDK fields, instanceof and collections in compiled code (these methods used to stay interpreted). */
public class JitStaticsTest {
    interface Shape { }
    static class Circle implements Shape { }
    static class Square implements Shape { }
    static int classify(Object o) {
        if (o instanceof Circle) return 1;
        if (o instanceof Shape) return 2;
        if (o instanceof String) return 3;
        return 0;
    }
    static int print(int i) {
        if (i % 25 == 0) System.out.println("tick " + i);
        return Integer.MAX_VALUE - i + Long.SIZE;
    }
    static int collect(int n) {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(i * i);
        int s = 0;
        for (int v : l) s += v;
        Map<String, Integer> m = new HashMap<>();
        m.put("k", s);
        return m.get("k") + (Boolean.TRUE ? 1 : 0);
    }
    public static void main(String[] args) {
        Object[] things = { new Circle(), new Square(), "s", 5 };
        int acc = 0;
        for (int i = 0; i < 60; i++) acc += classify(things[i % 4]) + print(i) % 10 + collect(i % 8);
        System.out.println(acc);
    }
}
