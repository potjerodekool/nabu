package io.github.potjerodekool.nabu.type;

import java.util.List;

/**
 * A union type.
 * <p> </p>
 * Used is a multi catch block.
 * <p> </p>
 *  try {
 *      //Do something
 *  } catch (IOException | ParseException e) {
 *      //Handle exception
 *  }
 */
public interface UnionType extends TypeMirror {
    List<? extends TypeMirror> getAlternatives();
}
