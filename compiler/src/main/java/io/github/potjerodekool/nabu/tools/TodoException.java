package io.github.potjerodekool.nabu.tools;

/**
 * An exception when some functionality isn't implemented yet.
 */
public class TodoException extends RuntimeException {

    public TodoException() {
        super();
    }

    public TodoException(final String message) {
        super(message);
    }
}
