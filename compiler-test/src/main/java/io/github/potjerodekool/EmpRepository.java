package io.github.potjerodekool;

import io.github.potjerodekool.employee.Employee;
//import io.github.potjerodekool.nabu.lang.jpa.support.JpaPredicate;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

public class EmpRepository {
/*
    JpaPredicate<Employee> findByIsActive(boolean isActive) {
        return (Root<Employee>e , CriteriaQuery<?> q, CriteriaBuilder cb) -> {
            final Path<Boolean> isActivePath = e.get("isActive");

            return cb.equal(isActivePath, isActive);
        };
    }
    */
}
