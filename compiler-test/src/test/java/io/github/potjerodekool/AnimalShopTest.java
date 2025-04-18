package io.github.potjerodekool;

import org.junit.jupiter.api.Test;

import io.github.potjerodekool.nabu.example.Dog;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AnimalShopTest {

    @Test
    void test() {
        final var shop = new AnimalShop();
        final var dog = new Dog();
        shop.addAnimal(dog);
        assertEquals(dog, shop.getAnimals().getFirst());
    }
}
