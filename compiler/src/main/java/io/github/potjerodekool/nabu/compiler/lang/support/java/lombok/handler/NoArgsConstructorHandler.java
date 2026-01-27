package io.github.potjerodekool.nabu.compiler.lang.support.java.lombok.handler;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.ElementFilter;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.util.Types;

public class NoArgsConstructorHandler extends AbstractAnnotationHandler {

    private final CompilerContext compilerContext;
    private final Types types;

    public NoArgsConstructorHandler(final CompilerContext compilerContext) {
        this.compilerContext = compilerContext;
        this.types = compilerContext.getTypes();
    }

    @Override
    public String getAnnotationName() {
        return "lombok.NoArgsConstructor";
    }

    @Override
    public void handle(final ClassSymbol classSymbol) {
        if (ElementFilter.constructorsIn(classSymbol.getEnclosedElements()).stream()
                .anyMatch(constructor -> constructor.getParameters().isEmpty())) {
            //Already has a no-arg constructor
            return;
        }

        if (ElementFilter.fieldsIn(classSymbol.getEnclosedElements()).stream()
                .anyMatch(Element::isFinal)) {
            //Has final fields
            return;
        }

        final var builder = compilerContext.getElementBuilders().executableElementBuilder();
        final var constructor = (Symbol) builder
                .kind(ElementKind.CONSTRUCTOR)
                .simpleName(Constants.INIT)
                .returnType(types.getNoType(TypeKind.VOID))
                        .build();

        classSymbol.addEnclosedElement(constructor);
    }
}
