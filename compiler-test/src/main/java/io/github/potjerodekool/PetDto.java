package io.github.potjerodekool;

import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;

import java.util.Set;
import java.util.UUID;

@Data
//@Getter(value = AccessLevel.PUBLIC)
public class PetDto {

    private UUID id;

    private CategoryDto category;

    private String name;

    private Set<TagDto> tags;

    public void setId(UUID id) {
        this.id = id;
    }

    public PetDto id(UUID id) {
        this.id = id;
        return this;
    }

    public void setCategory(CategoryDto category) {
        this.category = category;
    }

    public void setName(String name) {
        this.name = name;
    }

    public PetDto name(String name) {
        this.name = name;
        return this;
    }

    public void setTags(final Set<TagDto> tags) {
        this.tags = tags;
    }
}
