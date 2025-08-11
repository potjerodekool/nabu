package io.github.potjerodekool.employee;

import io.github.potjerodekool.nabu.example.EmployeePredicates;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class EmployeePredicatesTest extends AbstractPredicateTest {

    private void setupTestData(final EntityManager em) {
        final var employee = new Employee();
        employee.setFirstName("Evert");
        employee.setLastName("Tigchelaar");
        employee.setActive(true);
        employee.setLevel(10);
        employee.setBirthDay(LocalDate.of(1981, 6, 27));
        persist(employee, em);

        final var company = new Company();
        company.setName("My Company");
        company.setEmployees(List.of(employee));
        employee.setCompany(company);
        persist(company, em);

        final var employee2 = new Employee();
        employee2.setFirstName("Piet");
        employee2.setLastName("Pruttel");
        employee2.setActive(false);
        employee2.setLevel(10);
        employee2.setBirthDay(LocalDate.of(1981, 6, 27));
        persist(employee2, em);

        final var employee3 = new Employee();
        employee3.setLastName("Bitterbal");
        employee3.setActive(false);
        employee3.setLevel(10);
        employee3.setBirthDay(LocalDate.of(1981, 6, 27));
        persist(employee3, em);
    }

    @Test
    void findByFirstName() {
        final var instance = new EmployeePredicates();

        final var jpaPredicate = instance.findByFirstName("Evert");

        execute(em -> {
            setupTestData(em);

            final var cb = em.getCriteriaBuilder();
            final var query = cb.createQuery(Employee.class);
            final var personRoot = query.from(Employee.class);

            final var predicate = jpaPredicate.toPredicate(
                    personRoot,
                    query,
                    cb
            );

            query.where(predicate);
            final var persons = em.createQuery(query).getResultList();
            assertEquals(1, persons.size());
            assertEquals("Evert", persons.getFirst().getFirstName());
        });
    }

    @Test
    void findByNotFirstName() {
        final var instance = new EmployeePredicates();

        final var jpaPredicate =
                instance.findByNotFirstName("Evert");

        execute(em -> {
            setupTestData(em);

            final var cb = em.getCriteriaBuilder();
            final var query = cb.createQuery(Employee.class);
            final var personRoot = query.from(Employee.class);

            final var predicate = jpaPredicate.toPredicate(
                    personRoot,
                    query,
                    cb
            );

            query.where(predicate);
            final var persons = em.createQuery(query).getResultList();
            assertEquals(1, persons.size());
            assertEquals("Piet", persons.getFirst().getFirstName());
        });
    }

    @Test
    void findByLastName() {
        final var instance = new EmployeePredicates();

        final var jpaPredicate =
                instance.findByLastName("Pruttel");

        execute(em -> {
            setupTestData(em);

            final var cb = em.getCriteriaBuilder();
            final var query = cb.createQuery(Employee.class);
            final var personRoot = query.from(Employee.class);

            final var predicate = jpaPredicate.toPredicate(
                    personRoot,
                    query,
                    cb
            );

            query.where(predicate);
            final var persons = em.createQuery(query).getResultList();
            assertEquals(1, persons.size());
            assertEquals("Pruttel", persons.getFirst().getLastName());
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void findByIsActive(final boolean active) {
        final var instance = new EmployeePredicates();

        final var jpaPredicate =
                instance.findByIsActive(active);

        execute(em -> {
            setupTestData(em);

            final var cb = em.getCriteriaBuilder();
            final var query = cb.createQuery(Employee.class);
            final var root = query.from(Employee.class);

            final var predicate = jpaPredicate.toPredicate(
                    root,
                    query,
                    cb
            );

            query.where(predicate);
            final var result = em.createQuery(query).getResultList();

            assertEquals(active ? 1 : 2, result.size());
            assertEquals(active, result.getFirst().isActive());
        });
    }

    @Test
    void findByActive() {
        final var instance = new EmployeePredicates();

        final var jpaPredicate = instance.findByActive();

        execute(em -> {
            setupTestData(em);

            final var cb = em.getCriteriaBuilder();
            final var query = cb.createQuery(Employee.class);
            final var root = query.from(Employee.class);

            final var predicate = jpaPredicate.toPredicate(
                    root,
                    query,
                    cb
            );

            query.where(predicate);
            final var result = em.createQuery(query).getResultList();

            assertEquals(1, result.size());
            assertTrue(result.getFirst().isActive());
        });
    }

    @Test
    void findByIsInActive() {
        final var instance = new EmployeePredicates();

        final var jpaPredicate =
                instance.findByInActive();

        execute(em -> {
            setupTestData(em);

            final var cb = em.getCriteriaBuilder();
            final var query = cb.createQuery(Employee.class);
            final var root = query.from(Employee.class);

            final var predicate = jpaPredicate.toPredicate(
                    root,
                    query,
                    cb
            );

            query.where(predicate);
            final var result = em.createQuery(query).getResultList();

            assertEquals(2, result.size());
            assertFalse(result.getFirst().isActive());
        });
    }

    @Test
    void findByNotIsActive() {
        final var instance = new EmployeePredicates();

        final var jpaPredicate =
                instance.findByNotActive();

        execute(em -> {
            setupTestData(em);

            final var cb = em.getCriteriaBuilder();
            final var query = cb.createQuery(Employee.class);
            final var root = query.from(Employee.class);

            final var predicate = jpaPredicate.toPredicate(
                    root,
                    query,
                    cb
            );

            query.where(predicate);
            final var result = em.createQuery(query).getResultList();

            assertEquals(2, result.size());
            assertFalse(result.getFirst().isActive());
        });
    }

    @Test
    void findByFirstNameIsNull() {
        final var instance = new EmployeePredicates();

        final var jpaPredicate =
                instance.findByFirstNameIsNull();

        execute(em -> {
            setupTestData(em);

            final var cb = em.getCriteriaBuilder();
            final var query = cb.createQuery(Employee.class);
            final var root = query.from(Employee.class);

            final var predicate = jpaPredicate.toPredicate(
                    root,
                    query,
                    cb
            );

            query.where(predicate);
            final var result = em.createQuery(query).getResultList();

            assertEquals(1, result.size());
            assertNull(result.getFirst().getFirstName());
        });
    }

    @Test
    void findByFirstNameIsNotNull() {
        final var instance = new EmployeePredicates();

        final var jpaPredicate = instance.findByFirstNameIsNotNull();

        execute(em -> {
            setupTestData(em);

            final var cb = em.getCriteriaBuilder();
            final var query = cb.createQuery(Employee.class);
            final var root = query.from(Employee.class);

            final var predicate = jpaPredicate.toPredicate(
                    root,
                    query,
                    cb
            );

            query.where(predicate);
            final var result = em.createQuery(query).getResultList();

            assertEquals(2, result.size());
            assertNotNull(result.getFirst().getFirstName());
            assertNotNull(result.getLast().getFirstName());
        });
    }

    @Test
    void findByBirthdayAfter() {
        final var instance = new EmployeePredicates();

        final var jpaPredicate =
                instance.findByBirthdayAfter(LocalDate.of(
                        1980,
                        1,
                        1
                ));

        execute(em -> {
            setupTestData(em);

            final var cb = em.getCriteriaBuilder();
            final var query = cb.createQuery(Employee.class);
            final var root = query.from(Employee.class);

            final var predicate = jpaPredicate.toPredicate(
                    root,
                    query,
                    cb
            );

            query.where(predicate);
            final var result = em.createQuery(query).getResultList();

            final var lastNames = result.stream()
                    .map(Employee::getLastName)
                    .toList();

            assertEquals(3, result.size());
            assertEquals(
                    List.of(
                            "Tigchelaar",
                            "Pruttel",
                            "Bitterbal"
                    ),
                    lastNames
            );
        });
    }

    @Test
    void findByFirstNameAndLastName() {
        final var instance = new EmployeePredicates();

        final var jpaPredicate =
                instance.findByFirstNameAndLastName(
                        "Piet",
                        "Pruttel"
                );

        execute(em -> {
            setupTestData(em);

            final var cb = em.getCriteriaBuilder();
            final var query = cb.createQuery(Employee.class);
            final var personRoot = query.from(Employee.class);

            final var predicate = jpaPredicate.toPredicate(
                    personRoot,
                    query,
                    cb
            );

            query.where(predicate);
            final var persons = em.createQuery(query).getResultList();
            assertEquals(1, persons.size());
            assertEquals("Piet", persons.getFirst().getFirstName());
            assertEquals("Pruttel", persons.getFirst().getLastName());
        });
    }

    @Test
    void findByFirstNameOrLastName() {
        final var instance = new EmployeePredicates();

        final var jpaPredicate =
                instance.findByFirstNameOrLastName(
                        "Evert",
                        "Pruttel"
                );

        execute(em -> {
            setupTestData(em);

            final var cb = em.getCriteriaBuilder();
            final var query = cb.createQuery(Employee.class);
            final var personRoot = query.from(Employee.class);

            final var predicate = jpaPredicate.toPredicate(
                    personRoot,
                    query,
                    cb
            );

            query.where(predicate);
            final var persons = em.createQuery(query).getResultList();
            assertEquals(2, persons.size());
            final var first = persons.getFirst();
            final var second = persons.getLast();

            assertEquals("Evert", first.getFirstName());
            assertEquals("Pruttel", second.getLastName());
        });
    }

}
