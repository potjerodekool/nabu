package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.TreePrinter;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;
import io.github.potjerodekool.nabu.compiler.tree.TreeMaker;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BoxerTest {

    private final ClassElementLoader loader = new AsmClassElementLoader();
    private final Types types = loader.getTypes();
    private final MethodResolver methodResolver = new MethodResolver(types);
    private final Boxer boxer = new Boxer(loader, methodResolver);

    @BeforeEach
    void setup() {
        loader.postInit();
    }

    @Test
    void visitDeclaredType() {
    }

    @Test
    void visitPrimitiveType() {
        assertEquals("""
                b.booleanValue()""",
                TreePrinter.print(boxer.visitPrimitiveType(types.getPrimitiveType(TypeKind.BOOLEAN), createIdentifier("b", "java.lang.Boolean"))));
        assertEquals("""
                c.charValue()""",
                TreePrinter.print(boxer.visitPrimitiveType(types.getPrimitiveType(TypeKind.CHAR), createIdentifier("c", "java.lang.Character"))));
        assertEquals("""
                b.byteValue()""",
                TreePrinter.print(boxer.visitPrimitiveType(types.getPrimitiveType(TypeKind.BYTE), createIdentifier("b", "java.lang.Byte"))));
        assertEquals("""
                c.charValue()""",
                TreePrinter.print(boxer.visitPrimitiveType(types.getPrimitiveType(TypeKind.CHAR), createIdentifier("c", "java.lang.Character"))));
        assertEquals("""
                i.intValue()""",
                TreePrinter.print(boxer.visitPrimitiveType(types.getPrimitiveType(TypeKind.INT), createIdentifier("i", "java.lang.Integer"))));
        assertEquals("""
                f.floatValue()""",
                TreePrinter.print(boxer.visitPrimitiveType(types.getPrimitiveType(TypeKind.FLOAT), createIdentifier("f", "java.lang.Float"))));
        assertEquals("""
                l.longValue()""",
                TreePrinter.print(boxer.visitPrimitiveType(types.getPrimitiveType(TypeKind.LONG), createIdentifier("l", "java.lang.Long"))));
        assertEquals("""
                d.doubleValue()""",
                TreePrinter.print(boxer.visitPrimitiveType(types.getPrimitiveType(TypeKind.DOUBLE), createIdentifier("d", "java.lang.Double"))));
    }

    private IdentifierTree createIdentifier(final String name,
                                            final String className) {
        final var type = loader.loadClass(className).asType();
        final var identifier = IdentifierTree.create(name);
        identifier.setType(type);
        return identifier;
    }
}