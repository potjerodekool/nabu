package io.github.potjerodekool.nabu.compiler.backend.ir.impl;

import io.github.potjerodekool.nabu.test.AbstractCompilerTest;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.compiler.backend.ir.CodeVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.Frame;
import io.github.potjerodekool.nabu.compiler.backend.ir.INode;
import io.github.potjerodekool.nabu.compiler.backend.ir.ToIType;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IPrimitiveType;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.VariableSymbol;
import io.github.potjerodekool.nabu.compiler.type.impl.CPrimitiveType;
import io.github.potjerodekool.nabu.test.TestUtils;
import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.tree.Tag;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.PrimitiveTypeTree;
import io.github.potjerodekool.nabu.tree.expression.builder.BinaryExpressionBuilder;
import io.github.potjerodekool.nabu.tree.expression.builder.FieldAccessExpressionBuilder;
import io.github.potjerodekool.nabu.tree.expression.impl.CIdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CLiteralExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CPrimitiveTypeTree;
import io.github.potjerodekool.nabu.tree.impl.CConstantCaseLabel;
import io.github.potjerodekool.nabu.tree.impl.CDefaultCaseLabel;
import io.github.potjerodekool.nabu.tree.statement.CaseStatement;
import io.github.potjerodekool.nabu.tree.statement.impl.CBlockStatementTree;
import io.github.potjerodekool.nabu.tree.statement.impl.CCaseStatement;
import io.github.potjerodekool.nabu.tree.statement.impl.CSwitchStatement;
import io.github.potjerodekool.nabu.tree.statement.impl.CVariableDeclaratorTree;
import io.github.potjerodekool.nabu.type.TypeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TranslateTest extends AbstractCompilerTest {

    final Translate translate = new Translate(getCompilerContext());

    @BeforeEach
    void setup() {
        TestUtils.resetLabels();    }

    @Test
    void visitBinaryExpression() {
        //createFakeClass("SomeClass");
        final var clazz = new ClassSymbolBuilder()
                .simpleName("SomeClass")
                .build();

        final var stringType = getCompilerContext().getSymbolTable()
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
        final var module = getCompilerContext().getSymbolTable().getUnnamedModule();

        final var clazz = new ClassSymbolBuilder()
                .simpleName("SomeClass")
                        .build();

        final var context = new TranslateContext();
        final var frame = new Frame();

        final var thisType = ToIType.toIType(clazz.asType());

        frame.allocateLocal(Constants.THIS, thisType, false);

        final var joinTypeIdentifier = new CIdentifierTree("JoinType");

        final var joinTypeClazz = getCompilerContext().getClassElementLoader().loadClass(
                module,
                "foo.JoinType"
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
                foo.JoinType
                INNER
                """;

        assertEquals(expected, actual);
    }

    @Test
    void visitFieldAccess2() {
        //createFakeClass("ShapeKind");
        final var module = getCompilerContext().getSymbolTable().getUnnamedModule();

        final var clazz = new ClassSymbolBuilder()
                .simpleName("SomeClass")
                        .build();

        final var context = new TranslateContext();
        final var frame = new Frame();

        final var thisType = ToIType.toIType(clazz.asType());

        frame.allocateLocal(Constants.THIS, thisType, false);

        final var shapeKindIdentifier = new CIdentifierTree("ShapeKind");

        final var shapeKindClazz = getCompilerContext().getClassElementLoader().loadClass(
                module,
                "foo.ShapeKind"
        );

        shapeKindIdentifier.setType(shapeKindClazz.asType());

        final var classClazz = getCompilerContext().getClassElementLoader().loadClass(null,"java.lang.Class");

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
                foo.ShapeKind
                class
                """;

        assertEquals(expected, actual);
    }

    @Test
    void visitFieldAccess3() {
        final var javaBase = getCompilerContext().getSymbolTable().getJavaBase();

        final var integerClass = getCompilerContext().getClassElementLoader().loadClass(javaBase, "java.lang.Integer");

        final var classField = (VariableSymbol) new VariableSymbolBuilderImpl()
                .kind(ElementKind.FIELD)
                .simpleName("b")
                .type(integerClass.asType())
                .build();

        final var clazz = new ClassSymbolBuilder()
                .simpleName("Fields")
                .kind(ElementKind.CLASS)
                .enclosedElement(classField)
                .build();

        final var selected = IdentifierTree.create("Fields");
        selected.setType(clazz.asType());

        final var field = IdentifierTree.create("b");
        field.setSymbol(classField);

        final var fieldAccess = new FieldAccessExpressionBuilder()
                .selected(selected)
                .field(field)
                .build();

        final var context = new TranslateContext();

        final var result = fieldAccess.accept(translate, context).unEx();

        final var printer = new CodePrinter();
        result.accept(printer,null);
        final var actual = printer.getText();
        final var expected = """
                Fields
                b
                """;

        assertEquals(expected, actual);
    }

    @Test
    void visitSwitchStatement() {
        final var varType = new CPrimitiveTypeTree(PrimitiveTypeTree.Kind.INT, -1, -1);
        varType.setType(new CPrimitiveType(TypeKind.INT));

        final var localSymbol = new VariableSymbol(
                ElementKind.LOCAL_VARIABLE,
                0,
                "a",
                varType.getType(),
                null,
                null
        );

        final var identifier = new CIdentifierTree("a");
        identifier.setSymbol(localSymbol);

        final var localVariable = new CVariableDeclaratorTree(
                Kind.LOCAL_VARIABLE,
                new Modifiers(0),
                varType,
                identifier,
                null,
                new CLiteralExpressionTree(10)
        );

        final var caseStatement = new CCaseStatement(

                CaseStatement.CaseKind.STATEMENT,
                List.of(
                        new CConstantCaseLabel(
                                new CLiteralExpressionTree(1),
                                -1,
                                -1
                        )
                ),
                new CBlockStatementTree(List.of(localVariable)),
                -1,
                -1
        );

        final var switchStatement = new CSwitchStatement(
                new CLiteralExpressionTree(10),
                List.of(caseStatement),
                -1,
                -1
        );

        final var context = new TranslateContext();
        final var frame = new Frame();
        context.frame = frame;
        frame.allocateLocal("a", IPrimitiveType.INT, false);

        final var result = switchStatement.accept(translate, context).unNx();

        final var printer = new CodePrinter();
        result.accept(printer,frame);
        final var actual = printer.getText();
        final var expected = """
                10
                LOOKUPSWITCH
                1: L1
                L1
                10
                ISTORE 0
                L0
                """;

        assertEquals(expected, actual);
    }

    @Test
    void visitSwitchStatementMultiCase() {
        final var varType = new CPrimitiveTypeTree(PrimitiveTypeTree.Kind.INT, -1, -1);
        varType.setType(new CPrimitiveType(TypeKind.INT));

        final var localSymbol = new VariableSymbol(
                ElementKind.LOCAL_VARIABLE,
                0,
                "a",
                varType.getType(),
                null,
                null
        );

        final var identifier = new CIdentifierTree("a");
        identifier.setSymbol(localSymbol);

        final var caseStatements = new ArrayList<CaseStatement>();

        for (int i = 0; i < 2; i++) {
            final var localVariable = new CVariableDeclaratorTree(
                    Kind.LOCAL_VARIABLE,
                    new Modifiers(0),
                    varType,
                    identifier,
                    null,
                    new CLiteralExpressionTree(10)
            );

            final var caseStatement = new CCaseStatement(
                    CaseStatement.CaseKind.STATEMENT,
                    List.of(
                            new CConstantCaseLabel(
                                    new CLiteralExpressionTree(i),
                                    -1,
                                    -1
                            )
                    ),
                    new CBlockStatementTree(List.of(localVariable)),
                    -1,
                    -1
            );
            caseStatements.add(caseStatement);
        }

        final var switchStatement = new CSwitchStatement(
                new CLiteralExpressionTree(10),
                caseStatements,
                -1,
                -1
        );

        final var context = new TranslateContext();
        final var frame = new Frame();
        context.frame = frame;
        frame.allocateLocal("a", IPrimitiveType.INT, false);

        final var result = switchStatement.accept(translate, context).unNx();

        final var printer = new CodePrinter();
        result.accept(printer,frame);
        final var actual = printer.getText();
        final var expected = """
                10
                LOOKUPSWITCH
                0: L1
                1: L2
                L1
                10
                ISTORE 0
                L2
                10
                ISTORE 0
                L0
                """;

        assertEquals(expected, actual);
    }

    @Test
    void visitSwitchStatementWithDefault() {
        final var varType = new CPrimitiveTypeTree(PrimitiveTypeTree.Kind.INT, -1, -1);
        varType.setType(new CPrimitiveType(TypeKind.INT));

        final var localSymbol = new VariableSymbol(
                ElementKind.LOCAL_VARIABLE,
                0,
                "a",
                varType.getType(),
                null,
                null
        );

        final var identifier = new CIdentifierTree("a");
        identifier.setSymbol(localSymbol);

        final var localVariable = new CVariableDeclaratorTree(
                Kind.LOCAL_VARIABLE,
                new Modifiers(0),
                varType,
                identifier,
                null,
                new CLiteralExpressionTree(10)
        );

        final var caseStatement = new CCaseStatement(
                CaseStatement.CaseKind.STATEMENT,
                List.of(
                        new CConstantCaseLabel(
                                new CLiteralExpressionTree(1),
                                -1,
                                -1
                        ),
                        new CDefaultCaseLabel(-1, -1)
                ),
                new CBlockStatementTree(List.of(localVariable)),
                -1,
                -1
        );

        final var switchStatement = new CSwitchStatement(
                new CLiteralExpressionTree(10),
                List.of(caseStatement),
                -1,
                -1
        );

        final var context = new TranslateContext();
        final var frame = new Frame();
        context.frame = frame;
        frame.allocateLocal("a", IPrimitiveType.INT, false);

        final var result = switchStatement.accept(translate, context).unNx();

        final var printer = new CodePrinter();
        result.accept(printer,frame);
        final var actual = printer.getText();
        final var expected = """
                10
                LOOKUPSWITCH
                1: L1
                default : L1
                L1
                10
                ISTORE 0
                L0
                """;

        assertEquals(expected, actual);
    }
}

class CodePrinter implements CodeVisitor<Frame> {

    private final StringBuilder builder = new StringBuilder();

    @Override
    public Temp visitUnknown(final INode node, final Frame param) {
        throw new UnsupportedOperationException(node.getClass().getName());
    }

    @Override
    public Temp visitExpList(final ExpList expList, final Frame param) {
        expList.getList().forEach(e -> e.accept(this, param));
        return null;
    }

    @Override
    public Temp visitTemp(final TempExpr tempExpr, final Frame param) {
        final var index = tempExpr.getTemp().getIndex();
        writeLn("LOAD " + index);
        return null;
    }

    @Override
    public Temp visitFieldAccess(final IFieldAccess fieldAccess, final Frame param) {
        fieldAccess.getSelected().accept(this, param);
        writeLn(fieldAccess.getName());
        return null;
    }

    @Override
    public Temp visitName(final Name name, final Frame param) {
        writeLn(name.getLabel().getName());
        return null;
    }

    private void writeLn(final String text) {
        builder.append(text);
        writeLn();
    }

    private void writeLn() {
        builder.append("\n");
    }

    @Override
    public Temp visitBinop(final BinOp binOp, final Frame param) {
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

    @Override
    public void visitSeq(final Seq seq, final Frame param) {
        seq.getLeft().accept(this, param);
        seq.getRight().accept(this, param);
    }

    @Override
    public void visitSwitchStatement(final ISwitchStatement switchStatement, final Frame param) {
        switchStatement.getCondition().unEx().accept(this, param);

        writeLn("LOOKUPSWITCH");

        final var keys = switchStatement.getKeys();
        final var labels = switchStatement.getLabels();

        for (var i = 0; i < keys.length; i++) {
            writeLn(keys[i] + ": " + labels[i].getName());
        }

        final var defaultLabel = switchStatement.getDefaultLabel();
        if (Arrays.stream(switchStatement.getLabels())
                .anyMatch(it -> it == defaultLabel)) {
            writeLn("default : " + defaultLabel.getName());
        }
    }

    @Override
    public Temp visitConst(final Const cnst, final Frame param) {
        writeLn("" + cnst.getValue());
        return null;
    }
    @Override
    public void visitLabelStatement(final ILabelStatement labelStatement, final Frame param) {
        writeLn(labelStatement.getLabel().getName());
    }

    @Override
    public void visitVariableDeclaratorStatement(final IVariableDeclaratorStatement variableDeclaratorStatement,
                                                 final Frame frame) {
        variableDeclaratorStatement.getInitExpression().accept(this, frame);
        final var type = variableDeclaratorStatement.getType();

        final var name = variableDeclaratorStatement.getSymbol().getSimpleName();
        final var index = frame.indexOf(name);

        if (type instanceof IPrimitiveType primitiveType) {
            switch (primitiveType.getKind()) {
                case INT -> writeLn("ISTORE " + index);
            }
        }
    }

    @Override
    public void visitExpressionStatement(final IExpressionStatement expressionStatement, final Frame frame) {
        expressionStatement.getExp().accept(this, frame);
    }

    public String getText() {
        return builder.toString();
    }
}