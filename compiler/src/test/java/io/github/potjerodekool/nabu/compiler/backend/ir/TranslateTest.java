package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.TestClassElementLoader;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;
import io.github.potjerodekool.nabu.compiler.tree.Tag;
import io.github.potjerodekool.nabu.compiler.tree.expression.builder.BinaryExpressionBuilder;
import io.github.potjerodekool.nabu.compiler.tree.expression.builder.FieldAccessExpressionBuilder;
import io.github.potjerodekool.nabu.compiler.tree.expression.impl.CIdentifierTree;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TranslateTest {

    final Translate translate = new Translate();

    private final TestClassElementLoader loader = new TestClassElementLoader();

    @Test
    void visitBinaryExpression() {
        final var clazz = new ClassSymbol();
        clazz.setSimpleName("SomeClass");

        final var stringType = loader.getSymbolTable()
                .getStringType();

        final var thisSymbol = new VariableSymbolBuilderImpl()
                .kind(ElementKind.LOCAL_VARIABLE)
                .simpleName("this")
                .type(clazz.asType())
                .build();

        final var nameField = new VariableSymbolBuilderImpl()
                .kind(ElementKind.FIELD)
                .simpleName("name")
                .type(stringType)
                .enclosingElement(clazz)
                .build();

        final var thisExpression = new CIdentifierTree("this");
        thisExpression.setSymbol(thisSymbol);

        final var nameFieldExpression = new CIdentifierTree("name");
        nameFieldExpression.setSymbol(nameField);

        final var fieldAccess = new FieldAccessExpressionBuilder()
                .selected(thisExpression)
                .field(nameFieldExpression)
                .build();

        final var nameLocalVariable = new VariableSymbolBuilderImpl()
                .kind(ElementKind.LOCAL_VARIABLE)
                .simpleName("name")
                .type(stringType)
                .build();

        final var nameLocalVariableExpression = new CIdentifierTree("name");
        nameLocalVariableExpression.setSymbol(nameLocalVariable);

        final var binary = new BinaryExpressionBuilder()
                .left(fieldAccess)
                .tag(Tag.ASSIGN)
                .right(nameLocalVariableExpression)
                .build();

        final var context = new TranslateContext();
        final var frame = new Frame();

        final var thisType = ToIType.toIType(clazz.asType());

        frame.allocateLocal(Constants.THIS, thisType, false);
        frame.allocateLocal("name", ToIType.toIType(stringType), false);

        context.frame = frame;

        final var exp = binary.accept(translate, context).unEx();

        final var printer = new CodePrinter();
        exp.accept(printer, null);
        final var actual = printer.getText();
        final var expected = """
                LOAD 0
                LOAD 1
                SomeClass
                name
                """;

        assertEquals(expected, actual);
    }

    @Test
    void visitFieldAccess() {
        final var clazz = new ClassSymbol();
        clazz.setSimpleName("SomeClass");

        final var context = new TranslateContext();
        final var frame = new Frame();

        final var thisType = ToIType.toIType(clazz.asType());

        frame.allocateLocal(Constants.THIS, thisType, false);

        final var joinTypeIdentifier = new CIdentifierTree("JoinType");

        final var joinTypeClazz = loader.loadClass(
                null,
                "JoinType"
        );

        joinTypeIdentifier.setType(joinTypeClazz.asType());

        final var innerElement = new VariableSymbolBuilderImpl()
                .kind(ElementKind.ENUM_CONSTANT)
                .simpleName("INNER")
                .enclosingElement(joinTypeClazz)
                .type(joinTypeClazz.asType())
                .build();

        final var innerIdentifier = new CIdentifierTree("INNER");
        innerIdentifier.setSymbol(innerElement);

        final var fieldAccess = new FieldAccessExpressionBuilder()
                .selected(joinTypeIdentifier)
                .field(innerIdentifier)
                .build();

        final var result = fieldAccess.accept(translate, context).unEx();

        final var printer = new CodePrinter();
        result.accept(printer,null);
        final var actual = printer.getText();
        final var expected = """
                JoinType
                INNER
                """;

        assertEquals(expected, actual);
    }

    @Test
    void visitFieldAccess2() {
        final var clazz = new ClassSymbol();
        clazz.setSimpleName("SomeClass");

        final var context = new TranslateContext();
        final var frame = new Frame();

        final var thisType = ToIType.toIType(clazz.asType());

        frame.allocateLocal(Constants.THIS, thisType, false);

        final var shapeKindIdentifier = new CIdentifierTree("ShapeKind");

        final var shapeKindClazz = loader.loadClass(
                null,
                "ShapeKind"
        );

        shapeKindIdentifier.setType(shapeKindClazz.asType());

        final var classClazz = loader.loadClass(null,"Class");

        final var classIdentifier = new CIdentifierTree("class");
        classIdentifier.setType(classClazz.asType());

        final var fieldAccess = new FieldAccessExpressionBuilder()
                .selected(shapeKindIdentifier)
                .field(classIdentifier)
                .build();

        final var result = fieldAccess.accept(translate, context).unEx();

        final var printer = new CodePrinter();
        result.accept(printer,null);
        final var actual = printer.getText();
        final var expected = """
                ShapeKind
                class
                """;

        assertEquals(expected, actual);
    }
}

class CodePrinter implements CodeVisitor<Object> {

    private final StringBuilder builder = new StringBuilder();

    @Override
    public Temp visitUnknown(final INode node, final Object param) {
        throw new UnsupportedOperationException(node.getClass().getName());
    }

    @Override
    public Temp visitExpList(final ExpList expList, final Object param) {
        expList.getList().forEach(e -> e.accept(this, param));
        return null;
    }

    @Override
    public Temp visitTemp(final TempExpr tempExpr, final Object param) {
        final var index = tempExpr.getTemp().getIndex();
        writeLn("LOAD " + index);
        return null;
    }

    @Override
    public Temp visitFieldAccess(final IFieldAccess fieldAccess, final Object param) {
        fieldAccess.getSelected().accept(this, param);
        writeLn(fieldAccess.getName());
        return null;
    }

    @Override
    public Temp visitName(final Name name, final Object param) {
        writeLn(name.getLabel().getName());
        return null;
    }

    private void write(final String text) {
        builder.append(text);
    }

    private void writeLn(final String text) {
        builder.append(text);
        writeLn();
    }

    private void writeLn() {
        builder.append("\n");
    }

    @Override
    public Temp visitBinop(final BinOp binOp, final Object param) {
        if (binOp.getTag() == Tag.ASSIGN) {
            final var left = binOp.getLeft();
            if (left instanceof ExpList expList) {
                final var list = expList.getList();
                list.getFirst().accept(this, param);
                binOp.getRight().accept(this, param);
                list.getLast().accept(this, param);
            } else if (left instanceof IFieldAccess fieldAccess) {
                fieldAccess.getSelected().accept(this, param);
                binOp.getRight().accept(this, param);
                writeLn(fieldAccess.getName());
            }
        } else {
            binOp.getLeft().accept(this, param);
            binOp.getRight().accept(this, param);
        }
        return null;
    }

    public String getText() {
        return builder.toString();
    }
}