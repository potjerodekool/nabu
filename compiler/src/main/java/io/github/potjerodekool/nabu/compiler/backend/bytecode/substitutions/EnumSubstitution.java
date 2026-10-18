package io.github.potjerodekool.nabu.compiler.backend.bytecode.substitutions;

/**
 * {@code Enum.valueOf(Class, String)} without {@code Class.enumConstantDirectory()}:
 * the JDK builds a {@code HashMap} from name to constant inside
 * {@code Class}, which a native executable provides in C. This searches the
 * constants ({@code Class.getEnumConstants()}, the compiled {@code values()})
 * instead; enums are small. Same exceptions and messages as the JDK.
 */
public final class EnumSubstitution {

    private EnumSubstitution() {
    }

    public static <T extends Enum<T>> T valueOf(final Class<T> enumClass, final String name) {
        final T[] constants = enumClass.getEnumConstants();
        if (constants == null) {
            throw new IllegalArgumentException(
                    new StringBuilder().append(enumClass.getName()).append(" is not an enum class").toString());
        }
        if (name == null) {
            throw new NullPointerException("Name is null");
        }
        for (final T constant : constants) {
            if (constant.name().equals(name)) {
                return constant;
            }
        }
        throw new IllegalArgumentException(new StringBuilder().append("No enum constant ")
                .append(enumClass.getCanonicalName()).append('.').append(name).toString());
    }
}
