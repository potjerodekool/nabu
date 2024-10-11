package io.github.potjerodekool.employee;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.Objects;

@Entity
@Getter
@Setter
public class Employee {

    @Id
    @GeneratedValue
    private Integer id;

    private String firstName;

    private String lastName;

    private boolean isActive;

    private LocalDate birthDay;

    private Integer level;

    @ManyToOne
    private Company company;

    @Override
    public boolean equals(final Object obj) {
        if (obj instanceof Employee otherEmployee) {
            return Objects.equals(this.id, otherEmployee.id);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(this.id);
    }
}
