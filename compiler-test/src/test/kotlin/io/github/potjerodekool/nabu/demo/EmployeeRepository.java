package io.github.potjerodekool.nabu.demo;

import io.github.potjerodekool.nabu.lang.jpa.support.JpaPredicate;
import io.github.potjerodekool.employee.Employee;

public class EmployeeRepository {

    public JpaPredicate<Employee> findByFirstName(String firstName) {
        return (e, q, cb) -> {
            String s = "Test";
            return cb.equal(e.get("firstName"), firstName);
        };
    }

    public JpaPredicate<Employee> findByActive() {
        return (e, q, cb) -> cb.equal(e.get("isActive"), true);
    }
}
