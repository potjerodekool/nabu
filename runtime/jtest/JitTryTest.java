/** try/catch/finally, switches and synchronized in compiled code: the same output with and without NABU_JIT=0. */
public class JitTryTest {
    static class AppException extends Exception { final int code; AppException(String m, int code) { super(m); this.code = code; } }
    static class NotFound extends AppException { NotFound(String m) { super(m, 404); } }

    static int risky(int i) throws AppException {
        if (i % 7 == 0) throw new NotFound("nf" + i);
        if (i % 5 == 0) throw new AppException("app" + i, i);
        if (i % 11 == 0) throw new IllegalStateException("ise" + i);
        return i * 2;
    }
    static int guarded(int i) {
        try {
            return risky(i);
        } catch (NotFound e) {
            return -404;
        } catch (AppException e) {
            return -e.code;
        } catch (RuntimeException e) {
            return -1;
        }
    }
    static int withFinally(int i) {
        int r = 0;
        try {
            r += 1;
            if (i % 3 == 0) throw new RuntimeException("x");
            r += 10;
        } catch (RuntimeException e) {
            r += 100;
        } finally {
            r += 1000;
        }
        return r;
    }
    static int divide(int a, int b) {
        try { return a / b; } catch (ArithmeticException e) { return -1; }
    }
    static int arrays(int[] a, int i) {
        try { return a[i]; } catch (ArrayIndexOutOfBoundsException e) { return -2; }
    }
    static int nulls(int[] a) {
        try { return a.length; } catch (NullPointerException e) { return -3; }
    }
    static int nested(int i) {
        try {
            try { return risky(i); } catch (NotFound e) { throw new IllegalArgumentException("wrapped"); }
        } catch (IllegalArgumentException e) { return -5; } catch (Exception e) { return -6; }
    }
    static int table(int x) {
        switch (x) { case 0: return 10; case 1: return 11; case 2: return 12; case 4: return 14; default: return -1; }
    }
    static int lookup(int x) {
        switch (x) { case -5: return 1; case 100: return 2; case 100000: return 3; default: return 0; }
    }
    static String strSwitch(String s) {
        switch (s) { case "a": return "A"; case "bb": return "BB"; default: return "?"; }
    }
    static final Object LOCK = new Object();
    static int counter;
    static int locked(int i) { synchronized (LOCK) { counter += i; return counter; } }

    public static void main(String[] args) {
        int acc = 0;
        for (int i = 0; i < 60; i++) acc += guarded(i) + withFinally(i) + divide(i, i % 4) + nested(i);
        System.out.println(acc);
        int[] arr = {1, 2, 3};
        int s = 0;
        for (int i = -1; i < 40; i++) s += arrays(arr, i % 5) + nulls(i % 6 == 0 ? null : arr);
        System.out.println(s);
        int t = 0;
        for (int i = -2; i < 40; i++) t += table(i) + lookup(i * 50 - 5);
        System.out.println(t);
        StringBuilder sb = new StringBuilder();
        String[] keys = {"a", "bb", "c"};
        for (int i = 0; i < 30; i++) sb.append(strSwitch(keys[i % 3]));
        System.out.println(sb);
        int l = 0;
        for (int i = 0; i < 30; i++) l += locked(i);
        System.out.println(l + "|" + counter);
    }
}
