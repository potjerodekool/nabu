# JPA plugin

Plugin for a JPA DSL.
With the JPA DSL you can write more readable JPA code without the need to use the typesafe
api directly.

To create an join you can cast it to type of join you want.
JPA DSL supports operator overloading if a CriteriaBuilder is in scope.
    
    fun findCompanyByEmployeeFirstName(employeeFirstName: String): JpaPredicate<Company> {
        return (c : Root<Company>, q: CriteriaQuery<?>, cb: CriteriaBuilder) -> {
            final var e = (InnerJoin<Company, Employee>) c.employees;
            return e.firstName == employeeFirstName;
        };
    }

The above code will be transformed to:

    fun findCompanyByEmployeeFirstName(employeeFirstName: String): JpaPredicate<Company> {
        return (c : Root<Company>, q: CriteriaQuery<?>, cb: CriteriaBuilder) -> {
            final var e = c.join("employees", JoinType.INNER);
            return cb.equals(e.get("firstName"), employeeFirstName);
        };
    }

As you can see the generated code doesn't use the typesafe criteria api.
The JPA plugin will do the type checking.

You may still use underscore classes
in your entities (@OneToMany(mappedBy=Employee_.company)). Maybe in the future the JPA DSL will have an alternative
for that so that those underscore classes are not needed anymore which will reduce your application size.