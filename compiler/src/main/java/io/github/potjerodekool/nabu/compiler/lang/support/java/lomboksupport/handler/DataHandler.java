package io.github.potjerodekool.nabu.compiler.lang.support.java.lomboksupport.handler;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.lang.Flags;
import io.github.potjerodekool.nabu.compiler.lang.model.element.ElementFilter;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.type.TypeKind;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DataHandler extends AbstractAnnotationHandler {

    //private final List<AnnotationHandler> handlers;
    private final CompilerContext compilerContext;


    public DataHandler(final GetterHandler getterHandler,
                       final SetterHandler setterHandler,
                       final RequiredArgsConstructorHandler requiredArgsConstructorHandler,
                       final CompilerContext compilerContext) {
        //this.handlers = Arrays.asList(getterHandler, setterHandler, requiredArgsConstructorHandler);
        this.compilerContext = compilerContext;
    }

    @Override
    public void handle(final ClassSymbol classSymbol) {
        final var types = compilerContext.getTypes();
        final var voidType = types.getNoType(TypeKind.VOID);

        final var fields = ElementFilter.fieldsIn(classSymbol.getEnclosedElements()).stream()
                .filter(field -> !field.hasFlag(Flags.STATIC))
                .toList();

        fields.forEach(field -> {
            if (LombokAccessUtils.findMethod(
                    LombokAccessUtils.createGetterName(field.getSimpleName()),
                    field.asType(),
                    Collections.emptyList(),
                    classSymbol,
                    types
            ).isEmpty()) {
                LombokAccessUtils.addGetterMethod(
                        field,
                        Flags.PUBLIC,
                        classSymbol,
                        compilerContext
                );
            }

            if (LombokAccessUtils.findMethod(
                    LombokAccessUtils.createSetterName(field.getSimpleName()),
                    voidType,
                    List.of(field.asType()),
                    classSymbol,
                    types
            ).isEmpty()) {
                LombokAccessUtils.addSetterMethod(
                        field,
                        Flags.PUBLIC,
                        classSymbol,
                        compilerContext
                );
            }
        });

        //this.handlers.forEach(handler -> handler.handle(classSymbol));
    }

    @Override
    public String getAnnotationName() {
        return "lombok.Data";
    }
}
