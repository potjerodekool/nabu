package io.github.potjerodekool.nabu.compiler;

import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    public static <E> void forEarchIndexed(final List<E> list,
                                           final BiConsumer<Integer, E> consumer) {
        for (int i = 0; i < list.size(); i++) {
            consumer.accept(i, list.get(i));
        }
    }


}
