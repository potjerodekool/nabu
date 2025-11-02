package io.github.potjerodekool.nabu.type;

import java.util.List;

/**
 * A type which is an intersection of multiple types.
 *  &lt;T extends Number &amp; Runnable&gt;
 */
public interface IntersectionType extends TypeMirror {

    List<? extends TypeMirror> getBounds();
}
