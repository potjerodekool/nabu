package io.github.potjerodekool.nabu.compiler.lang.support.java.lombok.handler;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.ElementFilter;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.util.Types;

public class RequiredArgsConstructorHandler extends AbstractAnnotationHandler {

    private final CompilerContext compilerContext;
    private final Types types;

    public RequiredArgsConstructorHandler(final CompilerContext compilerContext) {
        this.compilerContext = compilerContext;
        this.types = compilerContext.getTypes();
    }

    @Override
    public String getAnnotationName() {
        return "lombok.RequiredArgsConstructor";
    }

    @Override
    public void handle(final ClassSymbol classSymbol) {
        final var finalFields = ElementFilter.fieldsIn(classSymbol.getEnclosedElements()).stream()
                .filter(Element::isFinal)
                        .toList();

        if (finalFields.isEmpty()) {
            return;
        }

        final var builder = compilerContext.getElementBuilders().executableElementBuilder();
        final var constructor = (Symbol) builder
                .kind(ElementKind.CONSTRUCTOR)
                .simpleName(Constants.INIT)
                .returnType(types.getNoType(TypeKind.VOID))
                .parameters(
                        finalFields.stream()
                                .map(this::createParameter)
                                .toList()
                )
                .build();

        classSymbol.addEnclosedElement(constructor);
    }

    private VariableElement createParameter(final VariableElement field) {
        final var builder = compilerContext.getElementBuilders().variableElementBuilder();
        return builder
                .kind(ElementKind.PARAMETER)
                .type(field.asType())
                .simpleName(field.getSimpleName())
                .build();
    }
}
