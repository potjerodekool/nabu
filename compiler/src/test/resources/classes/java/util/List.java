package java.util;

public interface List<E> {

    java.util.Iterator<E> iterator();

    static <E> List<E> of(E e1) {
        return null;
    }
}