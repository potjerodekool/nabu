package java.lang;

public abstract class Enum<E extends java.lang.Enum<E>> {

    protected Enum(String name, int ordinal) {
        this.name = name;
        this.ordinal = ordinal;
    }

    public final int ordinal() {

        return 0;
    }

    public static <T extends Enum<T>> T valueOf(Class<T> enumClass,
                                                String name) {
        return null;
    }

}