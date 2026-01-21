package io.github.potjerodekool.nabu.compiler.resolve.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.*;
import io.github.potjerodekool.nabu.lang.model.element.ElementFilter;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.util.CollectionUtils;
import io.github.potjerodekool.nabu.util.Elements;

public class RecordTypeEnter implements Completer {

    private final Elements elements;

    public RecordTypeEnter(final Elements elements) {
        this.elements = elements;
    }

    @Override
    public void complete(final Symbol symbol) throws CompleteException {
        final var classSymbol = (ClassSymbol) symbol;
        final var compactConstructorOptional = ElementFilter.constructorsIn(classSymbol.getEnclosedElements()).stream()
                .filter(elements::isCompactConstructor)
                .findFirst();

        compactConstructorOptional.ifPresent(compactConstructor -> {
            addRecordComponents(compactConstructor, classSymbol);
            addRecordFields(compactConstructor, classSymbol);
            addRecordComponentAccessMethods(compactConstructor, classSymbol);
        });
    }

    private void addRecordComponentAccessMethods(final ExecutableElement compactConstructor,
                                                 final ClassSymbol currentClass) {
        compactConstructor.getParameters().forEach(parameter -> {
            final var method = new MethodSymbolBuilderImpl()
                    .flags(Flags.PUBLIC)
                    .kind(ElementKind.METHOD)
                    .simpleName(parameter.getSimpleName())
                    .returnType(parameter.asType())
                    .build();
            currentClass.addEnclosedElement(method);
        });
    }

    private void addRecordComponents(final ExecutableElement compactConstructor,
                                     final ClassSymbol currentClass) {
        final var recordComponents = compactConstructor.getParameters().stream()
                .map(param -> createRecordComponent(param, ElementKind.RECORD_COMPONENT))
                .toList();

        CollectionUtils.forEachIndexed(recordComponents, currentClass::addEnclosedElement);
    }

    private void addRecordFields(final ExecutableElement compactConstructor,
                                 final ClassSymbol currentClass) {
        final var fields = compactConstructor.getParameters().stream()
                .map(param -> createRecordComponent(
                        param,
                        ElementKind.FIELD
                ))
                .toList();
        //Place the fields after record components.
        final var offset = fields.size();

        CollectionUtils.forEachIndexed(fields, (index, field) ->
                currentClass.addEnclosedElement(offset + index, field));
    }

    private VariableSymbol createRecordComponent(final VariableElement parameter,
                                                 final ElementKind kind) {
        return new VariableSymbolBuilderImpl()
                .kind(kind)
                .type(parameter.asType())
                .simpleName(parameter.getSimpleName())
                .build();
    }

}
