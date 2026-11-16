import java.util.*;
public class BoxedValuesTest {
  static class P { final int x, y; P(int x, int y) { this.x = x; this.y = y; }
    @Override public boolean equals(Object o) { return o instanceof P p && p.x == x && p.y == y; }
    @Override public int hashCode() { return 31 * x + y; }
    @Override public String toString() { return "P(" + x + "," + y + ")"; } }
  public static void main(String[] a) {
    Map<P, String> m = new HashMap<>();
    m.put(new P(1, 2), "a"); m.put(new P(1, 2), "b"); m.put(new P(3, 4), "c");
    System.out.println(m.size() + " " + m.get(new P(1, 2)) + " " + m.containsKey(new P(3, 4)));
    List<P> l = new ArrayList<>(List.of(new P(5, 6)));
    System.out.println(l.contains(new P(5, 6)) + " " + l.indexOf(new P(5, 6)) + " " + new P(1, 1).equals(new P(1, 1)));
    Set<P> s = new HashSet<>(); s.add(new P(7, 8)); s.add(new P(7, 8));
    System.out.println(s.size() + " " + s);
    Object o = new P(2, 2);
    System.out.println(o.equals(new P(2, 2)) + " " + (o.hashCode() == new P(2, 2).hashCode()));
    Integer big1 = 5000000, big2 = 5000000;
    System.out.println(big1.equals(big2) + " " + big1.hashCode() + " " + Objects.equals(big1, big2));
    Long l1 = 5000000000L, l2 = 5000000000L;
    System.out.println(l1.equals(l2) + " " + (l1 + l2) + " " + l1);
    Double d1 = 1.5, d2 = 1.5;
    System.out.println(d1.equals(d2) + " " + (d1 + d2) + " " + d1 + " " + new ArrayList<>(List.of(0.5, 2.0)));
  }
}
