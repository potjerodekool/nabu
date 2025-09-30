package io.github.potjerodekool.dependencyinjection.bean;

public class BeanCreationException extends RuntimeException {

    public BeanCreationException(final Throwable cause) {
        super(cause);
    }
}
