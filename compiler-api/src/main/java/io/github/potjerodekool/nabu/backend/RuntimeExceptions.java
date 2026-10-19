package io.github.potjerodekool.nabu.backend;

import java.util.List;

/**
 * Exception classes that a native backend's C runtime throws itself (a null
 * receiver, a bad index, a failing native call). The runtime creates them as
 * real compiled objects, with their {@code (String)} constructor, so code
 * that catches one can call {@code getMessage()} and the like; the
 * reachability analysis keeps those constructors, and the backend emits a
 * table to find them ({@code nabu_exception_table}).
 */
public final class RuntimeExceptions {

    /** Internal class names. */
    public static final List<String> CLASSES = List.of(
            "java/lang/NullPointerException",
            "java/lang/ArrayIndexOutOfBoundsException",
            "java/lang/IndexOutOfBoundsException",
            "java/lang/IllegalArgumentException",
            "java/lang/ClassNotFoundException",
            "java/lang/InstantiationException",
            "java/lang/NoSuchMethodException",
            "java/lang/NegativeArraySizeException",
            "java/io/IOException");

    private RuntimeExceptions() {
    }
}
