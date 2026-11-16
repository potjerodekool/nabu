import java.util.ArrayList;
import java.util.List;

/** Boxing: the unboxing methods, Boolean and Character in Object context, string concatenation of primitives. */
public class BoxTest {
    public static void main(String[] args) {
        Integer i = 5;
        System.out.println(i + 1);
        Object o = i;
        System.out.println(o);
        Boolean b = true;
        System.out.println(b);
        Object ob = b;
        System.out.println(ob);
        System.out.println(b.booleanValue());
        Character c = 'x';
        System.out.println(c);
        Long l = 7L;
        System.out.println(l + 1);
        List<Integer> list = new ArrayList<>();
        list.add(3);
        list.add(4);
        System.out.println(list.get(1));
        System.out.println(list.size());
        boolean flag = false;
        int zero = 0;
        String s = null;
        System.out.println("flag=" + flag + " zero=" + zero + " c=" + 'q' + " s=" + s + " b=" + b);
    }
}
