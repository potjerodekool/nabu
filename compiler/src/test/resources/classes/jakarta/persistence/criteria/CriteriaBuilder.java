package jakarta.persistence.criteria;

public interface CriteriaBuilder {

    <X extends Comparable<? super X>> Expression<X> least(Expression<X> x);

    Predicate equal(Expression<?> x, Object y);
}