package io.github.potjerodekool.nabu.compiler.lang.support.java.lombok.handler;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.lang.model.element.ElementFilter;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.util.Types;

import java.util.Optional;

/**
 * Adds getters to the AST when @Getter is used.
 */
public class GetterHandler extends AbstractAccessorAnnotationHandler {

    private final CompilerContext compilerContext;
    private final Types types;

    public GetterHandler(final CompilerContext compilerContext) {
        this.compilerContext = compilerContext;
        this.types = compilerContext.getTypes();
    }

    @Override
    public String getAnnotationName() {
        return "lombok.Getter";
    }

    private String createGetterName(final String fieldName) {
        return "get" + upperFirst(fieldName);
    }

    @Override
    protected Optional<ExecutableElement> findAccessorMethod(final String fieldName, final TypeMirror fieldType, final ClassSymbol classDeclaration) {
        final var getterName = createGetterName(fieldName);

        return ElementFilter.methodsIn(classDeclaration.getEnclosedElements()).stream()
                .filter(method -> getterName.equals(method.getSimpleName()))
                .filter(method -> method.getParameters().isEmpty())
                .filter(method -> types.isSameType(method.getReturnType(), fieldType))
                .findFirst();
    }

    @Override
    protected void addAccessorMethod(final VariableElement field, final long accessLevel, final ClassSymbol classSymbol) {
        final var builder = compilerContext.getElementBuilders().executableElementBuilder();

        final var getterName = createGetterName(field.getSimpleName());

        final var getter = (Symbol) builder
                .returnType(field.asType())
                .simpleName(getterName)
                .kind(ElementKind.METHOD)
                .flags(accessLevel)
                .build();

        classSymbol.addEnclosedElement(getter);
    }
}
