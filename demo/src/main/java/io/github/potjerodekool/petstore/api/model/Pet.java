package io.github.potjerodekool.petstore.api.model;

import java.util.UUID;

public class Pet {
    private UUID id;
    private String type;
    private String name;

    public UUID getId() {
        return this.id;
    }

    public void setId(final UUID id) {
        this.id = id;
    }

    public Pet id(final UUID id) {
        this.id = id;
        return this;
    }

    public String getType() {
        return this.type;
    }

    public void setType(final String type) {
        this.type = type;
    }

    public Pet type(final String type) {
        this.type = type;
        return this;
    }

    public String getName() {
        return this.name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public Pet name(final String name) {
        this.name = name;
        return this;
    }
}
