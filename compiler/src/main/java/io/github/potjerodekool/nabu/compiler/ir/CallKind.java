package io.github.potjerodekool.nabu.compiler.ir;

public enum CallKind {
    STATIC,
    VIRTUAL,
    INTERFACE,
    SPECIAL   // constructor of super
}