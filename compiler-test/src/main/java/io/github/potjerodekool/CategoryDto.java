package io.github.potjerodekool;

import java.util.UUID;

public class CategoryDto {

    private UUID id;
    private String name;

    public UUID getId() {
        return this.id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
