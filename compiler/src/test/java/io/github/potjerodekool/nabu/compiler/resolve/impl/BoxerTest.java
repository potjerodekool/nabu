package io.github.potjerodekool.nabu.compiler.resolve.impl;

import io.github.potjerodekool.nabu.lang.model.element.ModuleElement;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.testing.AbstractCompilerTest;
import io.github.potjerodekool.nabu.testing.TreePrinter;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.util.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BoxerTest extends AbstractCompilerTest {

    private ClassElementLoader loader;
    private Types types;
    private Boxer boxer;

    @BeforeEach
    void setUp() {
        loader = getCompilerContext().getClassElementLoader();
        types = getCompilerContext().getTypes();
        boxer = new Boxer(getCompilerContext());
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
        final var type = loader.loadClass(getModule(), className).asType();
        final var identifier = IdentifierTree.create(name);
        identifier.setType(type);
        return identifier;
    }

    private ModuleElement getModule() {
        return getCompilerContext().getModules().getUnnamedModule();
    }
}