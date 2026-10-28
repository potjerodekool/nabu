import java.util.ArrayList;
import java.util.Objects;

/**
 * The messages of the exceptions the JDK formats itself. The compiler leaves String.format out of these paths (so
 * that the Formatter, the locale providers and java.time are not part of every program): the messages are the same.
 */
public class Main {
    interface Check {
        void run() throws Exception;
    }

    static void show(String name, Check check) {
        try {
            check.run();
            System.out.println(name + ": no exception");
        } catch (Exception e) {
            System.out.println(name + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        show("list", () -> new ArrayList<String>().get(5));
        show("index", () -> Objects.checkIndex(7, 3));
        show("fromTo", () -> Objects.checkFromToIndex(2, 1, 5));
        show("fromSize", () -> Objects.checkFromIndexSize(3, 4, 5));
        show("substring", () -> "abc".substring(2, 9));
        show("parseInt", () -> Integer.parseInt("x1"));
        show("radixLow", () -> Integer.parseInt("1", 1));
        show("radixHigh", () -> Integer.parseInt("1", 99));
        show("parseLong", () -> Long.parseLong("12ab"));
        show("unsignedMinus", () -> Integer.parseUnsignedInt("-1"));
        show("unsignedRange", () -> Integer.parseUnsignedInt("99999999999"));
        show("codePoint", () -> Character.toChars(0x110000));
        show("uri", () -> new java.net.URI("a b"));
        show("noFile", () -> java.nio.file.Files.readAllBytes(java.nio.file.Path.of("definitely-missing.txt")));
        show("fileExists", () -> System.out.println(java.nio.file.Files.exists(java.nio.file.Path.of("definitely-missing.txt"))));
        // a regex without CANON_EQ and named characters keeps working
        System.out.println(java.util.regex.Pattern.compile("[a-c]+\\d").matcher("xabc7").find());
        // the program's own String.format is the real Formatter
        System.out.println(String.format("%d|%s|%x", 5, "x", 255));
    }
}
