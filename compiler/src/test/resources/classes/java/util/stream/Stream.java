package java.util.stream;

public interface Stream<T> extends java.util.stream.BaseStream<T, java.util.stream.Stream<T>> {

    <R, A> R collect(java.util.stream.Collector<? super T, A, R> collector);

}
