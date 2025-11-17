package io.github.potjerodekool.nabu.compiler.ast.element.builder.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ErrorSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.VariableSymbol;
import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.lang.model.element.PackageElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.lang.model.element.builder.*;

public final class ElementBuildersImpl implements ElementBuilders {

    private static final ElementBuildersImpl INSTANCE = new ElementBuildersImpl();

    public static ElementBuilders getInstance() {
        return INSTANCE;
    }

    private ElementBuildersImpl() {
    }

    public VariableElementBuilder<VariableSymbol> variableElementBuilder() {
        return new VariableSymbolBuilderImpl();
    }

    public VariableElementBuilder<VariableSymbol> variableElementBuilder(final VariableElement variableElement) {
        return new VariableSymbolBuilderImpl((VariableSymbol) variableElement);
    }

    @Override
    public ExecutableElementBuilder<? extends ExecutableElement> executableElementBuilder() {
        return new MethodSymbolBuilderImpl();
    }

    @Override
    public TypeElementBuilder<? extends TypeElement> typeElementBuilder() {
        return new ClassSymbolBuilder();
    }

    @Override
    public PackageElementBuilder<? extends PackageElement> packageElementBuilder() {
        return new PackageSymbolBuilder();
    }

    @Override
    public TypeElement createErrorSymbol(final String name) {
        return new ErrorSymbol(name);
    }


}


