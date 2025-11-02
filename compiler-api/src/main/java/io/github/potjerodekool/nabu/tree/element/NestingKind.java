package io.github.potjerodekool.nabu.tree.element;

/**
 * An enumeration of nesting kinds of an element.
 */
public enum NestingKind {
    /** Top level in a file. */
    TOP_LEVEL,
    /** As a member of another element. */
    MEMBER,
    /** Defined locally for example inside a method. */
    LOCAL,
    /**
     * Defined anonymously.
     * For example:
     * final var supplier = new Supplier&lt;String&gt;() {
     *   public String get() {
     *       return "Hello world!";
     *   }
     * };
     */
    ANONYMOUS
}
