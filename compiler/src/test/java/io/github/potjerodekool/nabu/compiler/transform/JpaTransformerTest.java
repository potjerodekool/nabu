package io.github.potjerodekool.nabu.compiler.transform;

import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.ast.element.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.PackageElement;
import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.resolve.SymbolTable;
import io.github.potjerodekool.nabu.compiler.resolve.Types;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;
import io.github.potjerodekool.nabu.compiler.tree.expression.AsExpression;
import io.github.potjerodekool.nabu.compiler.tree.expression.CFieldAccessExpression;
import io.github.potjerodekool.nabu.compiler.tree.expression.CIdent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

class JpaTransformerTest {

    private JpaTransformer transformer;
    private Types types;
    private final SymbolTable symbolTable = new SymbolTable();

    private AsmClassElementLoader loader;

    @BeforeEach
    void setup() {
        loader = new AsmClassElementLoader(symbolTable);
        final var context = new CompilerContext(loader);

        transformer = new JpaTransformer(
                context
        );
        this.types = loader.getTypes();

        final var joinClass = new ClassBuilder()
                .name("io.github.potjerodekool.nabu.lang.jpa.Join")
                .kind(ElementKind.INTERFACE)
                .build();

        addClassSymbol(joinClass);

        final var innerJoinClass = new ClassBuilder()
                .name("io.github.potjerodekool.nabu.lang.jpa.InnerJoin")
                .interfaceType(
                        types.getDeclaredType(joinClass)
                )
                .build();
        addClassSymbol(innerJoinClass);
    }

    private void addClassSymbol(final ClassSymbol classSymbol) {
        final var internalName = classSymbol.getQualifiedName().replace('.', '/');
        symbolTable.addClassSymbol(internalName, classSymbol);
    }


    @Test
    void visitAsExpression() {
        final var joinClass = loader.resolveClass("io.github.potjerodekool.nabu.lang.jpa.Join");

        final var asExpression = new AsExpression();
        final var fieldAccessExpression = new CFieldAccessExpression();
        fieldAccessExpression.setTarget(
                new CIdent("c")
        );
        fieldAccessExpression.setField(
                new CIdent("persons")
        );

        asExpression.setExpression(fieldAccessExpression);

        final var innerJoinClass = loader.resolveClass("io.github.potjerodekool.nabu.lang.jpa.InnerJoin");

        final var innerJoinType = types.getDeclaredType(innerJoinClass);
        final var innerJoinIdent = new CIdent("io.github.potjerodekool.nabu.lang.jpa.InnerJoin");

        innerJoinIdent.setType(innerJoinType);

        asExpression.setTargetType(innerJoinIdent);

        asExpression.accept(transformer, null);
    }

}