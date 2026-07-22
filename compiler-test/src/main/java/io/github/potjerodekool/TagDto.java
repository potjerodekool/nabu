package io.github.potjerodekool;

import java.util.UUID;

public class TagDto {

    private UUID id;
    private String name;

    public UUID getId() {
        return this.id;
    }

    public void setId(final UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }
}
