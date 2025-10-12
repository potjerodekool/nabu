package io.github.potjerodekool.petstore.api;

import io.github.potjerodekool.petstore.api.model.Pet;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping({"/v2/pets"})
public class CatController {

    private final List<Pet> pets = new ArrayList<>();
    private Pet pet;

    public CatController() {
        this.pet = new Pet();
        pet.setId(UUID.randomUUID());
        pet.setType("Cate");
        pet.setName("Sylvester J. Pussycat Sr.");
    }

    @GetMapping
    public List<Pet> getPets() {
        return this.pets;
    }
}
