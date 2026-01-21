package io.github.potjerodekool.nabu.compiler.backend.lower.widen;

import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.test.AbstractCompilerTest;
import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.expression.LiteralExpressionTree;
import io.github.potjerodekool.nabu.type.PrimitiveType;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.type.TypeVisitor;
import io.github.potjerodekool.nabu.util.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class WideningConverterTest extends AbstractCompilerTest {

    private final ClassElementLoader loader = getCompilerContext().getClassElementLoader();
    private final Types types = getCompilerContext().getTypes();
    private final WideningConverter wideningConverter = new WideningConverter(getCompilerContext());

    @AfterEach
    void teardown() throws Exception {
        loader.close();
    }

    private LiteralExpressionTree createLiteral(final Object literal) {
        final var literalExpression = TreeMaker.literalExpressionTree(literal, -1, -1);
        final var type = switch (literalExpression.getLiteralKind()) {
            case BOOLEAN -> types.getPrimitiveType(TypeKind.BOOLEAN);
            case BYTE -> types.getPrimitiveType(TypeKind.BYTE);
            case SHORT -> types.getPrimitiveType(TypeKind.SHORT);
            case INTEGER -> types.getPrimitiveType(TypeKind.INT);
            case LONG -> types.getPrimitiveType(TypeKind.LONG);
            case FLOAT -> types.getPrimitiveType(TypeKind.FLOAT);
            case DOUBLE -> types.getPrimitiveType(TypeKind.DOUBLE);
            case CHAR -> types.getPrimitiveType(TypeKind.CHAR);
            default -> null;
        };
        literalExpression.setType(type);
        return literalExpression;
    }

    private TypeMirror createType(final Object literal) {
        return switch (literal) {
            case Byte ignored -> types.getPrimitiveType(TypeKind.BYTE);
            case Short ignored -> types.getPrimitiveType(TypeKind.SHORT);
            case Integer ignored -> types.getPrimitiveType(TypeKind.INT);
            case Long ignored -> types.getPrimitiveType(TypeKind.LONG);
            case Float ignored -> types.getPrimitiveType(TypeKind.FLOAT);
            case Double ignored -> types.getPrimitiveType(TypeKind.DOUBLE);
            default -> throw new TodoException();
        };
    }

    @Test
    void wideningPrimitiveByte() {
        assertPrimitiveWidening((byte) 10, (short) 10);
        assertPrimitiveWidening((byte) 10, 10);
        assertPrimitiveWidening((byte) 10, 10L);
        assertPrimitiveWidening((byte) 10, 10F);
        assertPrimitiveWidening((byte) 10, 10D);
    }

    @Test
    void wideningPrimitiveShort() {
        assertPrimitiveWidening((short) 10, 10);
        assertPrimitiveWidening((short) 10, 10L);
        assertPrimitiveWidening((short) 10, 10F);
        assertPrimitiveWidening((short) 10, 10D);
    }

    @Test
    void wideningPrimitiveChar() {
        assertPrimitiveWidening('A', 10);
        assertPrimitiveWidening('A', 10L);
        assertPrimitiveWidening('A', 10F);
        assertPrimitiveWidening('A', 10D);
    }

    @Test
    void wideningPrimitiveInt() {
        assertPrimitiveWidening(10, 10L);
        assertPrimitiveWidening(10, 10F);
        assertPrimitiveWidening(10, 10D);
    }

    @Test
    void wideningPrimitiveLong() {
        assertPrimitiveWidening(10L, 10F);
        assertPrimitiveWidening(10L, 10D);
    }

    @Test
    void wideningPrimitiveFloat() {
        assertPrimitiveWidening(10F, 10D);
    }

    private void assertPrimitiveWidening(final Object left,
                                         final Object right) {
        final var leftLiteral = createLiteral(left);
        var result = (LiteralExpressionTree) wideningConverter.convert(
                leftLiteral,
                createLiteral(right)
        );

        TypeAsserter.assertTypes(
                createType(right),
                result.getType()
        );
    }

}

class TypeAsserter implements TypeVisitor<Void, TypeMirror> {

    private static final TypeAsserter asserter = new TypeAsserter();

    public static void assertTypes(final TypeMirror expected, final TypeMirror actual) {
        expected.accept(asserter, actual);
    }


    @Override
    public Void visitUnknownType(final TypeMirror typeMirror, final TypeMirror param) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Void visitPrimitiveType(final PrimitiveType primitiveType, final TypeMirror otherType) {
        assertKinds(primitiveType, otherType);
        return null;
    }

    private void assertKinds(final TypeMirror left,
                             final TypeMirror right) {
        Assertions.assertEquals(left.getKind(), right.getKind());
    }
}