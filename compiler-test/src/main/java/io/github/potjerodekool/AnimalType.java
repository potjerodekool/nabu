package io.github.potjerodekool;

public enum AnimalType {

    DOG("dog"),
    CAT("cat");

    private final String type;

    AnimalType(final String type) {
        this.type = type;
    }
}