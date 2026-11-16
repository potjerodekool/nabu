/** Core language operations in the interpreter: instanceof, toString in concatenation and println, switch, doubles in strings. */
public class CoreOpsTest {
    interface Named { String name(); default String greet() { return "hi " + name(); } }
    static class Dog implements Named {
        public String name() { return "rex"; }
        public String toString() { return "Dog(" + name() + ")"; }
    }
    static class Cat { public String toString() { return "Cat"; } }
    static String kind(Object o) {
        if (o instanceof Dog) return "dog";
        if (o instanceof Named) return "named";
        if (o instanceof Cat) return "cat";
        return "other";
    }
    static int sw(int x) { switch (x) { case 1: return 10; case 2: return 20; case 100: return 5; default: return -1; } }
    static String tsw(int x) { return switch (x) { case 1 -> "one"; case 2 -> "two"; default -> "many"; }; }
    public static void main(String[] args) {
        Dog d = new Dog();
        System.out.println(kind(d) + "|" + kind(new Cat()) + "|" + kind("s"));
        System.out.println("pet " + d + " and " + new Cat());
        System.out.println(d);
        System.out.println(d.greet());
        System.out.println(sw(1) + "|" + sw(2) + "|" + sw(100) + "|" + sw(7) + "|" + tsw(2) + "|" + tsw(9));
        double x = 2.5; float f = 1.5f; long l = 1L << 40; char c = 'z'; boolean b = true;
        System.out.println("v=" + x + "|" + f + "|" + l + "|" + c + "|" + b + "|" + (x * 2) + "|" + (int) x);
        System.out.println(x);
        System.out.println(Math.abs(-2.5) + "|" + Math.sqrt(16) + "|" + Math.max(1.5, 2.5) + "|" + Math.pow(2, 10) + "|" + Math.floor(2.7));
        System.out.println(Character.isDigit('5') + "|" + Character.toUpperCase('a') + "|" + Character.isLetter('x'));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) sb.append(i).append(',');
        System.out.println(sb);
        String s = "Hello, World";
        System.out.println(s.toUpperCase() + "|" + s.length() + "|" + s.substring(7) + "|" + s.indexOf("W") + "|" + s.charAt(1) + "|" + s.replace('l', 'L') + "|" + s.contains("World"));
    }
}
