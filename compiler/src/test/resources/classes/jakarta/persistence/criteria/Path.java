package jakarta.persistence.criteria;

public interface Path<X> extends Expression<X> {

    <Y> Path<Y> get(String attributeName);
}