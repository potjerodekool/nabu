package io.github.potjerodekool.nabu.demo;

import io.github.potjerodekool.nabu.lang.jpa.JpaPredicate;
import io.github.potjerodekool.employee.Employee;
import jakarta.persistence.criteria.JoinType;

public class EmployeeRepository {

    public JpaPredicate<Employee> findByFirstName(final String firstName) {
        return (p, query, cb) -> cb.equal(p.get("firstName"), firstName);
    }

    public JpaPredicate<Employee> findByIsActive(final boolean active) {
        return (p, query, cb) -> cb.equal(p.get("isActive"), active);
    }

    public JpaPredicate<Employee> findByIsActive() {
        return (p, query, cb)
                -> cb.equal(p.get("isActive"), true);
    }

    public JpaPredicate<Employee> findByInActive() {
        return (p, query, cb)
                -> cb.not(p.get("isActive"));
    }

    public JpaPredicate<Employee> findByIsNotActive() {
        return (p, query, cb) -> {
            var b = true;
            return cb.equal(p.get("isActive"), !b);
        };
    }

    public JpaPredicate<Employee> findEmployeeByCompanyName(final String companyName) {
        return (e, query, cb) -> {
            final var companyJoin = e.join("company", JoinType.INNER);
            return cb.equal(companyJoin.get("name"), companyName);
        };
    }
}
