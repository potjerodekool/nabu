package io.github.potjerodekool.nabu.compiler.util;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public final class CollectionUtils {

    private CollectionUtils() {
    }

    public static <E> List<E> concat(final List<E> a, final List<E> b) {
        if (a.isEmpty()) {
            return b.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(b);
        } else if (b.isEmpty()) {
            return Collections.unmodifiableList(a);
        } else {
            return Stream.concat(a.stream(), b.stream())
                    .collect(Collectors.toList());
        }
    }

    public static <E> List<E> headAndTailList(final E head, final List<E> tail) {
        final var list = Stream.concat(Stream.of(head), tail.stream()).collect(Collectors.toList());
        validateList(list);
        return list;
    }

    public static <E> List<E> tailOf(final List<E> list) {
        return list.isEmpty() ? Collections.emptyList() :
                list.stream().skip(1).collect(Collectors.toList());
    }

    private static <E> void validateList(final List<E> list) {
        list.forEach(element -> {
            if (element == null) {
                throw new NullPointerException();
            }
        });
    }

    public static <E> void forEachIndexed(final List<E> list,
                                          final BiConsumer<Integer, E> consumer) {
        for (int i = 0; i < list.size(); i++) {
            consumer.accept(i, list.get(i));
        }
    }

    public static <E> Stream<Pair<Integer, E>> streamWithIndex(final Collection<E> collection) {
        return StreamSupport.stream(
                new SpliteratorWithIndex<>(collection),
                false
        );
    }

    /**
     * @param first  A List
     * @param second Another List
     * <p>
     * Will throw an IllegalArgumentException if the size of the lists don't match.
     * @return Returns a Stream of pairs where
     * every pair contains a value from the first list and a value from the second list.
     */
    public static <A, B> Stream<Pair<A, B>> pairStream(final List<? extends A> first,
                                                       final List<? extends B> second) {
        if (first.size() != second.size()) {
            throw new IllegalArgumentException("Cannot create pair Stream");
        }

        return StreamSupport.stream(new PairSpliterator<>(first, second), false);
    }

    /**
     * @param resultClass
     * @param <T>
     * @param <R>
     * @return
     */
    public static <T, R> Function<T, Stream<R>> mapOnly(final Class<R> resultClass) {
        return t -> {
            if (resultClass.isAssignableFrom(t.getClass())) {
                return Stream.of(resultClass.cast(t));
            }

            return Stream.empty();
        };
    }
}

class PairSpliterator<A, B> implements Spliterator<Pair<A, B>> {

    private final List<? extends A> left;
    private final List<? extends B> right;
    private int pos = 0;

    public PairSpliterator(final List<? extends A> left,
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

class SpliteratorWithIndex<E> implements Spliterator<Pair<Integer, E>> {

    private final Iterator<E> iterator;
    private final long size;
    int position = -1;

    public SpliteratorWithIndex(final Collection<E> collection) {
        this.iterator = collection.iterator();
        this.size = collection.size();
    }


    @Override
    public boolean tryAdvance(final Consumer<? super Pair<Integer, E>> action) {
        if (iterator.hasNext()) {
            final var element = iterator.next();
            position++;
            action.accept(new Pair<>(position, element));
            return iterator.hasNext();
        }

        return false;
    }

    @Override
    public Spliterator<Pair<Integer, E>> trySplit() {
        return null;
    }

    @Override
    public long estimateSize() {
        return size;
    }

    @Override
    public int characteristics() {
        return SIZED;
    }
}