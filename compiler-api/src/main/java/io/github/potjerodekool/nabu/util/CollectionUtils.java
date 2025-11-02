package io.github.potjerodekool.nabu.util;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Utility methods for working with collections.
 */
public final class CollectionUtils {

    private CollectionUtils() {
    }

    /**
     * @param a A list.
     * @param b Another list.
     * @return Returns an immutable list with the elements from both lists.
     * @param <E> The type of the elements.
     */
    public static <E> List<E> concat(final List<E> a,
                                     final List<E> b) {
        if (a.isEmpty()) {
            return b.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(b);
        } else if (b.isEmpty()) {
            return Collections.unmodifiableList(a);
        } else {
            return Stream.concat(a.stream(), b.stream())
                    .toList();
        }
    }

    /**
     * @param head A head object.
     * @param tail A list with objects.
     * @return Returns an immutable list where the first element is the head element followed by the element from the tail list.
     * @param <E> The type of the elements.
     */
    public static <E> List<E> headAndTailList(final E head,
                                              final List<E> tail) {
        return Stream.concat(Stream.of(head), tail.stream()).toList();
    }

    /**
     * @param list A list.
     * @return Returns An immutable list with the element from the given list except the first element.
     * If the given list is empty then an empty list is returned.
     * @param <E> The type of the elements in the list.
     */
    public static <E> List<E> tailOf(final List<E> list) {
        return list.isEmpty() ? Collections.emptyList() :
                list.stream().skip(1).toList();
    }

    /**
     * An indexed for each.
     * @param list A list.
     * @param consumer A BiConsumer.
     * @param <E> The element types of the elements in the list.
     */
    public static <E> void forEachIndexed(final List<E> list,
                                          final BiConsumer<Integer, E> consumer) {
        for (int i = 0; i < list.size(); i++) {
            consumer.accept(i, list.get(i));
        }
    }

    /**
     * @param first  A List
     * @param second Another List
     * <p> </p>
     * Will throw an IllegalArgumentException if the size of the lists don't match.
     * @return Returns a Stream of pairs where
     * every pair contains a value from the first list and a value from the second list.
     */
    public static <A, B> Stream<Pair<A, B>> pairStream(final List<? extends A> first,
                                                       final List<? extends B> second) {
        if (first.size() != second.size()) {
            throw new IllegalArgumentException("Cannot create pair Stream");
        }

        return StreamSupport.stream(new DuoSpliterator<>(first, second), false);
    }

    /**
     * @param resultClass the result class.
     * @param <T> Input type.
     * @param <R> Result type.
     * @return Returns a function to be used in a stream to map only values of a given type.
     * <p> </p>
     * final var numbers = List.of(Integer.valueOf(1), Double.valueOf(1.5), Integer.valueOf(2));
     * <p> </p>
     * // Will contain only Integer Objects.
     * final var integers = numbers.stream()
     *  .flatMap(CollectionUtils.mapOnly(Integer.class))
     *  .toList();
     * <p> </p>
     *  //Without this function you would write
     * final var integer = numbers.stream()
     *  .filter(number -> number instance Integer)
     *  .map(number -> (Integer) number)
     *  .toList();
     */
    public static <T, R> Function<T, Stream<R>> mapOnly(final Class<R> resultClass) {
        return t -> t != null && resultClass.isAssignableFrom(t.getClass()) ? Stream.of(resultClass.cast(t))
                :Stream.empty();
    }

    /**
     * A Spliterator that operates on two lists.
     * @param <A>
     * @param <B>
     */
    private static class DuoSpliterator<A, B> implements Spliterator<Pair<A, B>> {

        private final List<? extends A> left;
        private final List<? extends B> right;
        private int pos = 0;

        public DuoSpliterator(final List<? extends A> left,
                              final List<? extends B> right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public boolean tryAdvance(final Consumer<? super Pair<A, B>> action) {
            if (pos == 0 && left.isEmpty()) {
                return false;
            }

            final var pair = new Pair<A, B>(
                    left.get(pos),
                    right.get(pos)
            );
            pos++;
            action.accept(pair);
            return pos < left.size();
        }

        @Override
        public Spliterator<Pair<A, B>> trySplit() {
            return null;
        }

        @Override
        public long estimateSize() {
            return left.size();
        }

        @Override
        public int characteristics() {
            return SIZED;
        }
    }
}

