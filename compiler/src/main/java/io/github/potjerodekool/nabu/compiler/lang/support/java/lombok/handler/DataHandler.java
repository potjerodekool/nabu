package io.github.potjerodekool.nabu.compiler.lang.support.java.lombok.handler;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;

import java.util.Arrays;
import java.util.List;

public class DataHandler extends AbstractAnnotationHandler {

    private final List<AnnotationHandler> handlers;


    public DataHandler(final GetterHandler getterHandler,
                       final SetterHandler setterHandler,
                       final RequiredArgsConstructorHandler requiredArgsConstructorHandler) {
        this.handlers = Arrays.asList(getterHandler, setterHandler, requiredArgsConstructorHandler);
    }

    @Override
    public void handle(final ClassSymbol classSymbol) {
        this.handlers.forEach(handler -> handler.handle(classSymbol));
    }

    @Override
    public String getAnnotationName() {
        return "lombok.Data";
    }
}
