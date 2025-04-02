package io.github.potjerodekool.nabu.compiler.backend.generate;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementFilter;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.backend.ir.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IExpressionStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.Move;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.Seq;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IPrimitiveType;
import io.github.potjerodekool.nabu.compiler.tree.Tag;
import io.github.potjerodekool.nabu.compiler.util.CollectionUtils;
import io.github.potjerodekool.nabu.compiler.util.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class RecordFiller {

    private final Elements elements;

    public RecordFiller(final Elements elements) {
        this.elements = elements;
    }

    public void fillRecord(final ClassSymbol classSymbol) {
        final var compactConstructorOptional = ElementFilter.constructorsIn(classSymbol.getEnclosedElements()).stream()
                .filter(elements::isCompactConstructor)
                .findFirst();

        compactConstructorOptional
                .map(it -> (MethodSymbol) it)
                .ifPresent(compactConstructor -> {
                    fillCompactConstructor(compactConstructor, classSymbol);
                    fillComponentAccessMethods(compactConstructor, classSymbol);
                });
    }

    private void fillCompactConstructor(final MethodSymbol compactConstructor,
                                        final ClassSymbol classSymbol) {
        final var parameters = compactConstructor.getParameters();
        final List<IStatement> statements = new ArrayList<>();
        final var frame = new Frame();
        final var type = classSymbol.asType().accept(ToIType.INSTANCE, null);
        frame.allocateLocal(
                Constants.THIS,
                type,
                false
        );

        statements.add(
                new IExpressionStatement(
                        new Call(
                                InvocationType.SPECIAL,
                                new Name(Constants.RECORD),
                                new Name(Constants.INIT),
                                IPrimitiveType.VOID,
                                List.of(),
                                List.of(
                                        new Mem(new TempExpr(0, type))
                                )
                        )
                )
        );

        CollectionUtils.forEachIndexed(parameters,
                (index, parameter) -> {
                    final var paramType = parameter.asType().accept(ToIType.INSTANCE, null);

                    statements.add(
                            new IExpressionStatement(
                                    new BinOp(
                                            new IFieldAccess(
                                                    classSymbol.getQualifiedName(),
                                                    parameter.getSimpleName(),
                                                    paramType,
                                                    false
                                            ),
                                            Tag.ASSIGN,
                                            new TempExpr(index + 1, paramType)
                                    )
                            )
                    );

                    frame.allocateLocal(
                            parameter.getSimpleName(),
                            parameter.asType().accept(ToIType.INSTANCE, null),
                            true
                    );
                }
        );

        statements.add(new Move(
                new TempExpr(),
                new TempExpr(Frame.V0)
        ));

        final var frag = new ProcFrag(
                Seq.seq(statements)
        );

        compactConstructor.setFrag(frag);
    }

    private void fillComponentAccessMethods(final MethodSymbol compactConstructor,
                                            final ClassSymbol classSymbol) {
        final var componentNames = compactConstructor.getParameters().stream()
                .map(Element::getSimpleName)
                .collect(Collectors.toSet());

        ElementFilter.methods(classSymbol).stream()
                .filter(it -> componentNames.contains(it.getSimpleName())
                        && it.getParameters().isEmpty())
                .map(it -> (MethodSymbol) it)
                .forEach(this::fillComponentAccessMethod);
    }

    private void fillComponentAccessMethod(final MethodSymbol componentAccessMethod) {
        final var clazz = (ClassSymbol) componentAccessMethod.getEnclosingElement();

        final var frame = new Frame();

        final var thisType = clazz.asType().accept(ToIType.INSTANCE, null);

        frame.allocateLocal("this", thisType, false);

        final List<IStatement> statements = List.of(
                new Move(
                        new Eseq(
                                new IExpressionStatement(
                                        new Mem(new TempExpr(0, thisType))
                                ),
                                new IFieldAccess(
                                        clazz.getQualifiedName(),
                                        componentAccessMethod.getSimpleName(),
                                        componentAccessMethod.getReturnType().accept(ToIType.INSTANCE, null),
                                        false
                                )
                        ),
                        new TempExpr(Frame.V0)
                )
        );

        final var frag = new ProcFrag(Seq.seq(statements));

        componentAccessMethod.setFrag(frag);
    }
}
