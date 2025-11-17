package jakarta.persistence.criteria;

public interface From<Z, X> extends Path<X> {

    <X, Y> Join<X, Y> join(String attributeName, JoinType jt);
}