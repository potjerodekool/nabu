package io.github.potjerodekool.nabu.compiler.resolve.scanner;

public sealed interface SearchResult permits
        EmptyResult,
        FileMatchResult {
}
