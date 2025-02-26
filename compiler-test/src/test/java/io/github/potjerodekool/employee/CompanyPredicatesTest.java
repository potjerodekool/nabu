package io.github.potjerodekool.employee;

//import io.github.potjerodekool.nabu.example.CompanyPredicates;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CompanyPredicatesTest extends AbstractPredicateTest {

    /*
    private void setupTestData(final EntityManager em) {
        final var employee = new Employee();
        employee.setFirstName("Evert");
        employee.setLastName("Tigchelaar");
        persist(employee, em);

        final var company = new Company();
        company.setEmployees(List.of(employee));
        employee.setCompany(company);
        persist(company, em);
    }

    @Test
    void findByFirstName() {
        final var instance = new CompanyPredicates();

        final var jpaPredicate = instance.findCompanyByEmployeeFirstName("Evert");

        execute(em -> {
            setupTestData(em);

            final var cb = em.getCriteriaBuilder();
            final var query = cb.createQuery(Company.class);
            final var companyRoot = query.from(Company.class);

            final var predicate = jpaPredicate.toPredicate(
                    companyRoot,
                    query,
                    cb
            );

            query.where(predicate);
            final var companies = em.createQuery(query).getResultList();
            assertEquals(1, companies.size());

            final var company = companies.getFirst();
            final var employeeCount = company.getEmployees().stream()
                            .filter(employee -> employee.getFirstName().equals("Evert"))
                                    .count();

            assertEquals(1L, employeeCount);
        });
    }
     */
}
