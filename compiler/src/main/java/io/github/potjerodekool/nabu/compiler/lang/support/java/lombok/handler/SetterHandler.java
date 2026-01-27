package io.github.potjerodekool.nabu.compiler.lang.support.java.lombok.handler;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.lang.model.element.ElementFilter;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.util.Types;

import java.util.Optional;

/**
 * Adds getters to the AST when @Setter is used.
 */
public class SetterHandler extends AbstractAccessorAnnotationHandler {

    private final CompilerContext compilerContext;
    private final Types types;

    public SetterHandler(final CompilerContext compilerContext) {
        this.compilerContext = compilerContext;
        this.types = compilerContext.getTypes();
    }

    @Override
    public String getAnnotationName() {
        return "lombok.Setter";
    }

    private String createSetterName(final String fieldName) {
        return "set" + upperFirst(fieldName);
    }

    @Override
    protected Optional<ExecutableElement> findAccessorMethod(final String fieldName, final TypeMirror fieldType, final ClassSymbol classDeclaration) {
        final var setterName = createSetterName(fieldName);

        return ElementFilter.methodsIn(classDeclaration.getEnclosedElements()).stream()
                .filter(method -> setterName.equals(method.getSimpleName()))
                .filter(method -> method.getParameters().size() == 1)
                .filter(method -> types.isSameType(method.getParameters().getFirst().asType(), fieldType))
                .findFirst();
    }

    @Override
    protected void addAccessorMethod(final VariableElement field, final long accessLevel, final ClassSymbol classDeclaration) {
        final var parameterBuilder = compilerContext.getElementBuilders().variableElementBuilder();
        final var parameter = parameterBuilder.kind(ElementKind.PARAMETER)
                .simpleName(field.getSimpleName())
                .type(field.asType())
                .build();


        final var executableElementBuilder = compilerContext.getElementBuilders().executableElementBuilder();

        final var setterName = createSetterName(field.getSimpleName());

        final var setter = (Symbol) executableElementBuilder
                .returnType(types.getNoType(TypeKind.VOID))
                .simpleName(setterName)
                .kind(ElementKind.METHOD)
                .flags(accessLevel)
                .parameter(parameter)
                .build();

        classDeclaration.addEnclosedElement(setter);
    }
}
