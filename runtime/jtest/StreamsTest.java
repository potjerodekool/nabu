import java.util.*;
import java.util.stream.*;

/** Streams, collectors, method references and constructor references through the JDK's own classes. */
public class StreamsTest {
    enum Color { RED, GREEN, BLUE }
    enum Op { ADD { int ap(int a) { return a + 1; } }, SUB { int ap(int a) { return a - 1; } }; abstract int ap(int a); }
    public static void main(String[] args) {
        List<Integer> l = List.of(1, 2, 3, 4);
        l.stream().forEach(x -> System.out.println("e" + x));
        System.out.println(l.stream().count() + "|" + l.stream().filter(x -> x % 2 == 0).count());
        System.out.println(IntStream.range(0, 5).sum() + "|" + l.stream().mapToInt(x -> x).sum());
        System.out.println(l.stream().map(x -> x * 2).collect(Collectors.toList()));
        System.out.println(l.stream().map(x -> "n" + x).collect(Collectors.joining(",")));
        System.out.println(l.stream().reduce(0, (x, y) -> x + y) + "|" + l.stream().map(x -> x * 2).toList());
        ArrayList<Integer> three = Stream.of(1, 2).collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        System.out.println(three);
        System.out.println(l.stream().sorted(Comparator.reverseOrder()).map(String::valueOf).collect(Collectors.joining("-")));
        System.out.println(Color.valueOf("GREEN") + "|" + EnumSet.of(Color.BLUE) + "|" + Op.ADD.getDeclaringClass().getSimpleName() + "|" + Op.SUB.ap(5));
        StringJoiner j = new StringJoiner(", ", "[", "]");
        j.add("x").add("y");
        System.out.println(j + "|" + j.length() + "|" + String.join("/", List.of("p", "q")));
        Map<Boolean, List<Integer>> parts = l.stream().collect(Collectors.partitioningBy(x -> x > 2));
        System.out.println(parts);
    }
}
