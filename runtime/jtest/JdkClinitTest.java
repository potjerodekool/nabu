import java.util.*;

/** JDK classes whose static initializers matter: empty-array constants, empty collections, tables. */
public class JdkClinitTest {
    public static void main(String[] args) {
        List<String> list = new ArrayList<>();
        list.add("a"); list.add("b"); list.add("c");
        System.out.println(list.size() + "|" + list.get(2));
        Map<String, Integer> map = new HashMap<>();
        map.put("x", 1); map.put("y", 2);
        System.out.println(map.size() + "|" + map.get("y"));
        System.out.println(Collections.emptyList().size());
        System.out.println(Arrays.asList(1, 2, 3).size());
        Optional<String> o = Optional.of("v");
        System.out.println(o.get() + "|" + Optional.empty().isPresent());
        StringBuilder sb = new StringBuilder();
        sb.append("n=").append(42);
        System.out.println(sb.toString());
        System.out.println(Integer.valueOf(7) + "|" + Integer.MAX_VALUE + "|" + Long.MIN_VALUE);
        Iterator<String> it = list.iterator();
        int n = 0; while (it.hasNext()) { it.next(); n++; }
        System.out.println(n);
    }
}
