package io.github.potjerodekool;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface PetRepository extends JpaRepository<Pet, UUID> {

    @Query(value = "")
    List<Pet> findByStatus(@Param(value = "statuses") List<String> statuses);
}
