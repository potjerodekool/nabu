package io.github.potjerodekool;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

public class PController {

    private PetRepository petRepository;

    public List<PetDto> getPets() {
        final var result = this.petRepository.findAll();
        final var resultList = new ArrayList<PetDto>();

        new HashSet<String>();

        for (var i = 0; i < result.size(); i++) {
            final var pet = (Pet) result.get(i);
            final var petDto = this.mapToDto(pet);
            resultList.add(petDto);
        }

        return resultList;
    }


    private PetDto mapToDto(Pet pet) {
        final var id = pet.getId();
        final var name = pet.getName();

        final var petDto = new PetDto();
        petDto.setId(id);
        petDto.setName(name);
        return petDto;
    }
}
