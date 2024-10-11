package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.FileObject;
import io.github.potjerodekool.nabu.compiler.frontend.parser.NabuCompilerParser;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.scope.FunctionScope;
import io.github.potjerodekool.nabu.compiler.tree.expression.CLambdaExpression;
import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatement;
import io.github.potjerodekool.nabu.lang.jpa.JpaPredicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Paths;

import static org.mockito.Mockito.when;

class Phase2ResolverTest {

    private final ClassElementLoader classElementLoader = new AsmClassElementLoader();

    private final CompilerContext compilerContext = new CompilerContext(
            classElementLoader
    );

    private boolean initClassPath = true;

    @BeforeEach
    public void setup() {
        if (!initClassPath) {
            return;
        }

        initClassPath = false;
        final var path = Paths.get("target/classes");
        classElementLoader.addClassPathEntry(path);
    }

    private FileObject createFileObject(final String src) throws IOException {
        final var fileObject = Mockito.mock(FileObject.class);
        when(fileObject.openInputStream())
                .thenAnswer(answer -> new ByteArrayInputStream(src.getBytes()));
        when(fileObject.getFileName())
                .thenReturn("PersonPredicates.nabu");
        return fileObject;
    }

    @Test
    void visitLambdaExpression() throws IOException {
        final var src = """
                import io.github.potjerodekool.person.Employee;
                fun findByFirstName(firstName: String): JpaPredicate<Person> {
                    return { p : Person ->
                        String s = "Test";
                        p.firstName == firstName;
                    }
                }
                """;
        final var fileObject = createFileObject(src);

        final NabuCompilerParser parser = new NabuCompilerParser();
        parser.parse(fileObject.openInputStream());
        final var compilationUnit = parser.parse(fileObject.openInputStream());

        final var lambda = new CLambdaExpression();
        final var lambdaBody = new BlockStatement();
        lambda.body(lambdaBody);
        final var lambdaType = classElementLoader.resolveType(JpaPredicate.class.getName());
        lambda.setType(lambdaType);

        final var resolver = new Phase2Resolver(compilerContext);

        final var scope = new FunctionScope(null, null);
        lambda.accept(resolver, scope);

        System.out.println(compilationUnit);
    }
}