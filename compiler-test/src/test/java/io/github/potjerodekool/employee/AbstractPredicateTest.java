package io.github.potjerodekool.employee;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;

import java.util.function.Consumer;

public abstract class AbstractPredicateTest {

    private EntityManagerFactory entityManagerFactory() {
        final var builder = new StandardServiceRegistryBuilder();
        final var registry = builder.build();
        final var configuration = new Configuration()
                .configure();

        configuration.addAnnotatedClass(Employee.class);
        configuration.addAnnotatedClass(Company.class);
        return configuration.buildSessionFactory(registry);
    }

    protected void persist(final Object entity,
                           final EntityManager entityManager) {
        entityManager.getTransaction().begin();
        entityManager.persist(entity);
        entityManager.getTransaction().commit();
    }

    protected void execute(final Consumer<EntityManager> action) {
        try (var emf = entityManagerFactory()) {
            final var em = emf.createEntityManager();
            action.accept(em);
        }
    }
}
