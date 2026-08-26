package io.github.potjerodekool.nabu.type;

/**
 * A variable type.
 * Used when the 'var' keyword is used instead of the type.
 */
public interface VariableType extends TypeMirror {

    /**
     * @return Returns the interfered type.
     * May return null if the type isn't interfered.
     */
    TypeMirror getInterferedType();
}
