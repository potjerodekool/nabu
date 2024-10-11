package io.github.potjerodekool.nabu.compiler.resolve.scanner;

final class EmptyResult implements SearchResult {

    public static final EmptyResult INSTANCE = new EmptyResult();

    private EmptyResult() {
    }

}
