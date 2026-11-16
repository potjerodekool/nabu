public class ArrTest {
    public static void main(String[] args) {
        int[] a = new int[5];
        for (int i = 0; i < a.length; i++) a[i] = i * 5;
        int s = 0;
        for (int i = 0; i < a.length; i++) s += a[i];
        System.out.println(s);           // 50
        char[] c = new char[3];
        c[0] = 'A';
        System.out.println(a.length - 2); // 3
        System.out.println(c[0]);         // A (char)
        int[][] m = new int[2][3];
        m[1][2] = 9;
        System.out.println(m[1][2]);      // 9
        String[] ss = new String[2];
        ss[0] = "x";
        System.out.println(ss[0]);
    }
}
