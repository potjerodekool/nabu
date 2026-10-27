public class Main {
    static void inner() throws Exception {
        throw new java.io.IOException("inner");
    }

    static void middle() throws Exception {
        try {
            inner();
        } catch (Throwable t) {
            throw new RuntimeException("wrapped", t);
        }
    }

    static void outer() throws Exception {
        try {
            middle();
        } catch (IllegalStateException e) {
            System.out.println("wrong");
        }
    }

    public static void main(String[] args) throws Exception {
        try {
            outer();
        } catch (RuntimeException e) {
            System.out.println(e.getMessage() + " " + e.getCause().getMessage());
        }
    }
}
