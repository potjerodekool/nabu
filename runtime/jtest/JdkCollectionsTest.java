import java.util.*;

/** JDK collections through their bytecode: iterators of inherited methods, interface calls to inherited methods, split, StringBuilder. */
public class JdkCollectionsTest {
    public static void main(String[] args) {
        Map<String, Integer> hm = new HashMap<>();
        hm.put("k", 1); hm.put("j", 2);
        int keys = 0;
        for (String k : hm.keySet()) keys += k.length();
        System.out.println(hm.size() + "|" + keys + "|" + hm.get("j"));
        Map<String, Integer> lm = new LinkedHashMap<>();
        lm.put("a", 1); lm.put("b", 2); lm.put("c", 3);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : lm.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
        System.out.println(sb);
        List<String> parts = new ArrayList<>();
        for (String p : "a,b,,c,,".split(",")) parts.add(p);
        System.out.println(parts.size() + "|" + parts.get(3));
        String[] words = "  the quick  brown fox ".trim().split("\\s+");
        System.out.println(words.length + "|" + words[2]);
        sb.setLength(0);
        sb.append(1L << 40).append(true).append(2.5).append(1.5f).append((Object) "x");
        System.out.println(sb.toString());
        Set<Integer> set = new HashSet<>(Arrays.asList(5, 7, 5, 9));
        int sum = 0;
        for (int v : set) sum += v;
        System.out.println(set.size() + "|" + sum);
        List<Integer> zeros = new LinkedList<>();
        zeros.add(0); zeros.add(5); zeros.add(0);
        Map<String, Integer> dm = new TreeMap<>();
        dm.put("a", 0); dm.put("b", 15);
        long total = 0;
        for (Integer z : zeros) total += z;
        System.out.println(zeros + "|" + total + "|" + dm + "|" + zeros.contains(0) + "|" + zeros.indexOf(5) + "|" + (zeros.get(0) == 0));
    }
}
