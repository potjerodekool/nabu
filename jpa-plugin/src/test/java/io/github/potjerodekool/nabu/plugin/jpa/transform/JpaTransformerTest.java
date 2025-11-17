package io.github.potjerodekool.nabu.plugin.jpa.transform;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.MethodSymbol;
import io.github.potjerodekool.nabu.lang.jpa.support.Join;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.resolve.scope.FunctionScope;
import io.github.potjerodekool.nabu.testing.AbstractCompilerTest;
import io.github.potjerodekool.nabu.testing.TreePrinter;
import io.github.potjerodekool.nabu.tools.transform.spi.CodeTransformer;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.statement.ReturnStatementTree;
import io.github.potjerodekool.nabu.type.TypeMirror;
import org.junit.jupiter.api.*;

import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JpaTransformerTest extends AbstractCompilerTest {

    private VariableElement createVariable(final TypeMirror typeMirror,
                                           final String name) {
        return getCompilerContext().getElementBuilders().variableElementBuilder()
                .kind(ElementKind.LOCAL_VARIABLE)
                .simpleName(name)
                .type(typeMirror)
                .build();
    }

    private TypeElement createClass() {
        final var packageSymbol = getCompilerContext().getElementBuilders().packageElementBuilder()
                .createUnnamed();

        return getCompilerContext().getElementBuilders().typeElementBuilder()
                .simpleName("MyClass")
                .kind(ElementKind.CLASS)
                .enclosingElement(packageSymbol)
                .build();
    }

    @Override
    protected String getClassPath() {
        final var location = Join.class.getProtectionDomain().getCodeSource().getLocation();
        try {
            return Paths.get(location.toURI()).toString();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    @Test
    void transform() {
        final CodeTransformer transformer = new JpaTransformer(getCompilerContext());

        final var loader = getCompilerContext().getClassElementLoader();
        final var module = getUnnamedModule();
        final var rootClass = loader.loadClass(module, "jakarta.persistence.criteria.Root");
        final var criteriaBuilderType = loader.loadClass(module, "jakarta.persistence.criteria.CriteriaBuilder").asType();
        final var specificationType = loader.loadClass(module, "org.springframework.data.jpa.domain.Specification").asType();
        final var companyType = loader.loadClass(module, "foo.Company").asType();

        final var rootType = loader.getTypes()
                .getDeclaredType(
                        rootClass,
                        companyType
                );

        final var cu = parse("""
                import jakarta.persistence.criteria.CriteriaBuilder;
                import jakarta.persistence.criteria.Root;
                import io.github.potjerodekool.nabu.lang.jpa.support.InnerJoin;
                import foo.Company;
                import foo.Employee;
                
                public class MyClass {
                
                    fun findCompanyByEmployeeFirstName(employeeFirstName: String): Specification<Company> {
                         return (c : Root<Company>, q: CriteriaQuery<?>, cb: CriteriaBuilder) -> {
                             var e = (InnerJoin<Company, Employee>) c.employees;
                             return e.firstName == employeeFirstName;
                         };
                     }
                }
                """, parentScope -> {
            final var scope = new FunctionScope(
                    parentScope,
                    null
            );

            scope.define(createClass());
            scope.define(createVariable(rootType, "c"));
            scope.define(createVariable(criteriaBuilderType, "cb"));

            return scope;
        });

        final Function function = TreePaths.select(cu, "MyClass.findCompanyByEmployeeFirstName");
        final var method = (MethodSymbol) function.getMethodSymbol();
        method.setReturnType(specificationType);

        final var returnStatement = (ReturnStatementTree) function.getBody().getStatements().getFirst();
        returnStatement.getExpression().setType(specificationType);
        transformer.transform(cu);

        cu.getClasses().forEach(this::removeConstructors);

        final var actual = TreePrinter.print(cu);
        final var expected = loadResource("JpaTransformerTest/expected.txt");
        assertEquals(expected, actual);
    }

    private void removeConstructors(final ClassDeclaration classDeclaration) {
        classDeclaration.getEnclosedElements().removeIf(
                it -> it instanceof Function function && function.getKind() == Kind.CONSTRUCTOR
        );
    }
}

class TreePaths {

    public static <T extends Tree> T select(final Tree tree,
                                            final String path) {
        return (T) select(tree, path.split("\\."), 0);
    }

    private static Tree select(final Tree tree,
                               final String[] pathElements,
                               final int index) {
        final var pathElement = pathElements[index];
        final var subTrees = subTrees(tree);
        final var subTreeOptional = subTrees.stream()
                .filter(it -> pathElement.equals(getName(it)))
                .findFirst();

        if (subTreeOptional.isPresent()) {
            final var subTree = subTreeOptional.get();

            if (index < pathElements.length - 1) {
                return select(subTree, pathElements, index + 1);
            } else {
                return subTree;
            }
        } else {
            return null;
        }
    }

    private static List<? extends Tree> subTrees(final Tree tree) {
        if (tree instanceof CompilationUnit compilationUnit) {
            return compilationUnit.getClasses();
        } else if (tree instanceof ClassDeclaration classDeclaration) {
            return classDeclaration.getEnclosedElements();
        } else {
            return Collections.emptyList();
        }
    }

    private static String getName(final Tree tree) {
        if (tree instanceof ClassDeclaration classDeclaration) {
            return classDeclaration.getSimpleName();
        } else if (tree instanceof io.github.potjerodekool.nabu.tree.element.Function function) {
            return function.getSimpleName();
        } else {
            return "";
        }
    }
}