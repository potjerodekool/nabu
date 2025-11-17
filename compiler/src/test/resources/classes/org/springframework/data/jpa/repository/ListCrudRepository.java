package org.springframework.data.repository;

import java.util.List;

public interface ListCrudRepository<T, ID> {

    List<T> findAll();
}