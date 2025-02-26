package io.github.potjerodekool.nabu.compiler.util;

import java.util.Collections;
import java.util.List;
import java.util.Spliterator;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
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

    /**
     * @param first A List
     * @param second Another List
     * @return Returns a Stream of pairs where
     * every pair contains a value from the first list and a value from the second list.
     * @param <E> Some type.
     *
     * Will throw an IllegalArgumentException if the size of the lists don't match.
     */
    public static <E> Stream<Pair<E>> pairStream(final List<? extends E> first,
                                                 final List<? extends E> second) {
        if (first.size() != second.size()) {
            throw new IllegalArgumentException("Cannot create pair Stream");
        }

        return StreamSupport.stream(new PairSpliterator<>(first, second), false);
    }
}

class PairSpliterator<E> implements Spliterator<Pair<E>> {

    private final List<? extends E> left;
    private final List<? extends E> right;
    private int pos = 0;

    public PairSpliterator(final List<? extends E> left,
                           final List<? extends E> right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public boolean tryAdvance(final Consumer<? super Pair<E>> action) {
        final var pair = new Pair<>(
                left.get(pos),
                right.get(pos)
        );
        pos++;
        action.accept(pair);
        return pos < left.size();
    }

    @Override
    public Spliterator<Pair<E>> trySplit() {
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