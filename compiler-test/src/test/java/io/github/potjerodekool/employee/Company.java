package io.github.potjerodekool.employee;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Entity
@Getter
@Setter
public class Company {

    @Id
    @GeneratedValue
    private Integer id;

    private String name;

    @OneToMany
    private List<Employee> employees;
}
