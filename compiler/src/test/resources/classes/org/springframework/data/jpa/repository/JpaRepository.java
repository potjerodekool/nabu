package org.springframework.data.jpa.repository;

import java.util.List;

public interface JpaRepository<T, ID>  extends ListCrudRepository<T, ID> {

    <S extends T> List<S> findAll(Example<S> example);

}