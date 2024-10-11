package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.compiler.backend.postir.PostIr;
import io.github.potjerodekool.nabu.compiler.frontend.desugar.lambda.LambdaToMethod;
import io.github.potjerodekool.nabu.compiler.diagnostic.ConsoleDiagnosticListener;
import io.github.potjerodekool.nabu.compiler.diagnostic.DelegateDiagnosticListener;
import io.github.potjerodekool.nabu.compiler.diagnostic.Diagnostic;
import io.github.potjerodekool.nabu.compiler.diagnostic.DiagnosticListener;
import io.github.potjerodekool.nabu.compiler.enhance.Enhancer;
import io.github.potjerodekool.nabu.compiler.backend.generate.CodeGenerator;
import io.github.potjerodekool.nabu.compiler.backend.ir.Translate;
import io.github.potjerodekool.nabu.compiler.frontend.parser.NabuCompilerParser;
import io.github.potjerodekool.nabu.compiler.frontend.parser.NabuCompilerVisitor;
import io.github.potjerodekool.nabu.compiler.resolve.*;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.transform.JpaTransformer;
import io.github.potjerodekool.nabu.compiler.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;
import io.github.potjerodekool.nabu.lang.jpa.JpaPredicate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NabuCompiler {

    private final Path targetDirectory = Paths.get("output");

    private final NabuCompilerParser parser = new NabuCompilerParser();

    private final ClassElementLoader classElementLoader = new AsmClassElementLoader();

    private final CompilerContext compilerContext = new CompilerContext(
            classElementLoader
    );

    private final ErrorCapturer errorCapturer = new ErrorCapturer(
            new ConsoleDiagnosticListener()
    );

    private final FileManager fileManager = new FileManager();

    private final Map<String, Class<? extends AbstractTreeVisitor<?, Scope>>> transformers = new HashMap<>();

    public NabuCompiler() {
        this.transformers.put(
                JpaPredicate.class.getName(),
                JpaTransformer.class
        );
    }


    public void addClassPathEntry(final Path path) {
        classElementLoader.addClassPathEntry(path);
    }

    public void compile(final Options options) throws IOException {
        init();

        final var files = options.getSourceRoots().stream()
                .flatMap(sourceRoot -> fileManager.listFiles(sourceRoot).stream())
                .toList();

        final var compilationUnits = new ArrayList<CompilationUnit>();

        for (final FileObject file : files) {
            final var units = process(file);
            compilationUnits.addAll(units);
        }

        compilationUnits.forEach(compilationUnit -> {
            final var generator = new CodeGenerator();

            final var packageDeclaration = compilationUnit.getPackageDeclaration();

            final var clazz = compilationUnit.getClasses().getFirst();
            generator.generate(clazz, options);
            final var bytecode = generator.getBytecode();

            final var name = clazz.getSimpleName();
            final var outputPath  = Path.of(name + ".class");

            Path outputDirectory;

            if (packageDeclaration != null) {
                final var packagePath = Paths.get(packageDeclaration.getPackageName()
                                .replace('.', File.separatorChar));
                outputDirectory = targetDirectory.resolve(packagePath);
            } else {
                outputDirectory = targetDirectory;
            }

            final var path = outputDirectory.resolve(outputPath);

            try {
                deleteIfExists(path);
                createDirectories(outputDirectory);
                Files.write(path, bytecode);
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void init() {
        classElementLoader.postInit();
    }

    private void createDirectories(final Path path) throws IOException {
        Files.createDirectories(path);
    }

    private void deleteIfExists(final Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var directoryStream = Files.newDirectoryStream(path)) {
                directoryStream.forEach(subPath -> {
                    try {
                        deleteIfExists(subPath);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        } else {
            Files.deleteIfExists(path);
        }
    }

    public List<CompilationUnit> process(final FileObject fileObject) throws IOException {
        final var oldErrorCount = errorCapturer.getErrorCount();

        var cu = parse(fileObject);
        cu = enchange(cu);
        resolvePhase1(cu, fileObject);
        cu = resolvePhase2(cu);
        cu = check(cu);

        final var newErrorCount = errorCapturer.getErrorCount();

        if (newErrorCount > oldErrorCount || newErrorCount > 100) {
            return List.of();
        }

        cu = lambdaToMethod(cu);
        ir(cu);
        return List.of(cu);
    }

    public CompilationUnit parse(final FileObject fileObject) throws IOException {
        try (var inputStream = fileObject.openInputStream()) {
            final var compilationUnitContext = parser.parse(inputStream);
            final var visitor = new NabuCompilerVisitor(fileObject);
            return (CompilationUnit) compilationUnitContext.accept(visitor);
        }
    }

    public void resolvePhase1(final CompilationUnit compilationUnit,
                              final FileObject fileObject) {
        final var phase1Resolver = new Phase1Resolver(
                compilerContext
        );
        compilationUnit.accept(phase1Resolver, null);

        compilationUnit.getClasses().stream()
                        .findFirst().ifPresent(classDeclaration -> {
                            final var classSymbol = classDeclaration.classSymbol;
                            classSymbol.setFileObject(fileObject);
                });
    }

    public CompilationUnit resolvePhase2(final CompilationUnit compilationUnit) {
        final var phase2Resolver = new Phase2Resolver(
                compilerContext
        );

        compilationUnit.accept(phase2Resolver, null);

        /*
        transformers.values().forEach(transformerClass -> {
            final var transformer = createTransformer(transformerClass);
            if (transformer != null) {
                compilationUnit.accept(transformer, null);
            }
        });
        */

        return compilationUnit;
    }

    private AbstractTreeVisitor<?, Scope> createTransformer(final Class<? extends AbstractTreeVisitor<?, Scope>> transformerClass) {
        try {
            final var constructor = transformerClass.getConstructor(
                    CompilerContext.class
            );
            return constructor.newInstance(compilerContext);
        } catch (final Exception e) {
            return null;
        }
    }

    private CompilationUnit transform(final CompilationUnit compilationUnit) {
        return compilationUnit;
    }

    private CompilationUnit check(final CompilationUnit compilationUnit) {
        final var checker = new Checker(errorCapturer);
        compilationUnit.accept(checker, null);
        return compilationUnit;
    }

    public CompilationUnit enchange(final CompilationUnit compilationUnit) {
        final var enhance = new Enhancer();
        compilationUnit.accept(enhance, null);
        return compilationUnit;
    }

    public void ir(final CompilationUnit compilationUnit) {
        final var toIr = new Translate();
        compilationUnit.accept(toIr, null);
        postIr(compilationUnit);
    }

    private void postIr(final CompilationUnit compilationUnit) {
        final var postIr = new PostIr();
        compilationUnit.accept(postIr, null);

    }


    public CompilationUnit lambdaToMethod(final CompilationUnit compilationUnit) {
        final var lamdaToMethod = new LambdaToMethod(classElementLoader);
        compilationUnit.accept(lamdaToMethod, null);
        return compilationUnit;
    }
}

class ErrorCapturer extends DelegateDiagnosticListener {

    private final List<Diagnostic> errors = new ArrayList<>();

    public ErrorCapturer(final DiagnosticListener delegate) {
        super(delegate);
    }

    @Override
    public void report(final Diagnostic diagnostic) {
        super.report(diagnostic);
        if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
            errors.add(diagnostic);
        }
    }

    public List<Diagnostic> getErrors() {
        return errors;
    }

    public int getErrorCount() {
        return errors.size();
    }
}