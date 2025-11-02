package java.util.stream;

public interface Stream<T> extends BaseStream<T, Stream<T>> {

    <R, A> R collect(Collector<? super T, A, R> collector);

}
