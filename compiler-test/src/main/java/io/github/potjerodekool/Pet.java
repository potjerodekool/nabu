package io.github.potjerodekool;

import java.util.UUID;

public class Pet {

    private UUID id;

    private String name;

    private PetStatus status;

    public PetStatus getStatus() {
        return status;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
