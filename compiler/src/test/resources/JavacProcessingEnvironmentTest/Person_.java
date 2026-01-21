import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.SetAttribute;
import jakarta.persistence.metamodel.SingularAttribute;
import jakarta.persistence.metamodel.StaticMetamodel;

public abstract class Person_ {
    public static final ID : String = "id";
    public static final FIRST_NAME : String = "firstName";
    public static final TAGS : String = "tags";
    public static class_ : EntityType<Person>;
    public static id : SingularAttribute<Person, Long>;
    public static firstName : SingularAttribute<Person, String>;
    public static tags : SetAttribute<Person, String>;
}
