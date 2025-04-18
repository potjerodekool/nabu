package io.github.potjerodekool;

import java.util.ArrayList;
import java.util.List;

import io.github.potjerodekool.nabu.example.Dog;

public class AnimalShop {

    private List<Animal> animals = new ArrayList<>();

    public List<Animal> getAnimals() {
        return animals;
    }

    public void addAnimal(final Dog dog) {
        this.animals.add(dog);
    }
}
