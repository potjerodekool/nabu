package io.github.potjerodekool.nabu.demo;

import io.github.potjerodekool.nabu.lang.jpa.support.JpaPredicate;
import io.github.potjerodekool.employee.Company;
import io.github.potjerodekool.employee.Employee;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;

public class CompanyRepository {

    public JpaPredicate<Company> findCompanyByEmployeeFirstName(final String firstName) {
        return (root, query, builder) -> {
            final Join<Company, Employee> e = root.join("employees", JoinType.INNER);
            return builder.equal(e.get("firstName"), firstName);
        };
    }
}

