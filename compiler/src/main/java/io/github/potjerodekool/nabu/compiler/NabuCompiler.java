package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.dependencyinjection.ApplicationContext;
import io.github.potjerodekool.dependencyinjection.ClassPathScanner;
import io.github.potjerodekool.nabu.compiler.ast.element.StandardElementMetaData;
import io.github.potjerodekool.nabu.compiler.backend.generate.asm.AsmByteCodeGenerator;
import io.github.potjerodekool.nabu.compiler.backend.ir.Translate;
import io.github.potjerodekool.nabu.compiler.backend.lower.Lower;
import io.github.potjerodekool.nabu.compiler.frontend.desugar.lambda.LambdaToMethod;
import io.github.potjerodekool.nabu.compiler.diagnostic.ConsoleDiagnosticListener;
import io.github.potjerodekool.nabu.compiler.diagnostic.DelegateDiagnosticListener;
import io.github.potjerodekool.nabu.compiler.diagnostic.Diagnostic;
import io.github.potjerodekool.nabu.compiler.diagnostic.DiagnosticListener;
import io.github.potjerodekool.nabu.compiler.enhance.Enhancer;
import io.github.potjerodekool.nabu.compiler.backend.generate.ByteCodeGenerator;
import io.github.potjerodekool.nabu.compiler.frontend.parser.NabuCompilerParser;
import io.github.potjerodekool.nabu.compiler.frontend.parser.NabuCompilerVisitor;
import io.github.potjerodekool.nabu.compiler.log.LogLevel;
import io.github.potjerodekool.nabu.compiler.log.Logger;
import io.github.potjerodekool.nabu.compiler.resolve.*;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;
import io.github.potjerodekool.nabu.compiler.transform.CodeTransformer;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class NabuCompiler implements AutoCloseable {

    private final Logger logger = Logger.getLogger(NabuCompiler.class.getName());

    private Path targetDirectory = Paths.get("output");

    private final NabuCompilerParser parser = new NabuCompilerParser();

    private final ClassElementLoader classElementLoader = new AsmClassElementLoader();

    private final ErrorCapturer errorCapturer = new ErrorCapturer(
            new ConsoleDiagnosticListener()
    );

    private final FileManager fileManager = new FileManager();

    private final ApplicationContext applicationContext = new ApplicationContext();

    public void addClassPathEntry(final Path path) {
        classElementLoader.addClassPathEntry(path);
    }

    public void compile(final Options options) throws IOException {
        if (options.getTargetDirectory() != null) {
            this.targetDirectory = options.getTargetDirectory();
        }

        init();

        final var compilerContext = new CompilerContext(
                classElementLoader,
                applicationContext
        );

        applicationContext.registerBean(CompilerContext.class, compilerContext);

        final var files = options.getSourceRoots().stream()
                .flatMap(sourceRoot -> fileManager.listFiles(sourceRoot).stream())
                .toList();

        final var compilationUnits = new ArrayList<CompilationUnit>();

        for (final FileObject file : files) {
            final var units = process(file, compilerContext);
            compilationUnits.addAll(units);
        }

        compilationUnits.forEach(compilationUnit -> {
            final ByteCodeGenerator generator = new AsmByteCodeGenerator();

            final var packageDeclaration = compilationUnit.getPackageDeclaration();

            final var clazz = compilationUnit.getClasses().getFirst();
            generator.generate(clazz, options);
            final var bytecode = generator.getBytecode();

            final var name = clazz.getSimpleName();
            final var outputPath = Path.of(name + ".class");

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
                logger.log(
                        LogLevel.INFO,
                        String.format("Generated %s", path.toAbsolutePath())
                );
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void init() {
        classElementLoader.postInit();
        scanForBeans();
    }

    private void scanForBeans() {
        final var scanner = new ClassPathScanner();
        final var beans = scanner.scan();
        applicationContext.registerBean(ClassElementLoader.class, classElementLoader);
        applicationContext.registerBeans(beans);
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

    public List<CompilationUnit> process(final FileObject fileObject,
                                         final CompilerContext compilerContext) throws IOException {
        final var oldErrorCount = errorCapturer.getErrorCount();

        var cu = parse(fileObject);
        cu = enchange(cu);
        resolvePhase1(
                cu,
                fileObject,
                compilerContext
        );
        cu = resolvePhase2(cu, compilerContext);
        transform(cu);
        cu = resolvePhase2(cu, compilerContext);
        check(cu);

        final var newErrorCount = errorCapturer.getErrorCount();

        if (newErrorCount > oldErrorCount || newErrorCount > 100) {
            return List.of();
        }

        cu = lambdaToMethod(cu);
        cu = lower(cu, compilerContext);
        ir(cu);
        return List.of(cu);
    }

    public CompilationUnit parse(final FileObject fileObject) throws IOException {
        logger.log(LogLevel.INFO, "Parsing " + fileObject.getFileName());

        try (var inputStream = fileObject.openInputStream()) {
            final var compilationUnitContext = parser.parse(inputStream);
            final var visitor = new NabuCompilerVisitor(fileObject);
            return (CompilationUnit) compilationUnitContext.accept(visitor);
        }
    }

    public void resolvePhase1(final CompilationUnit compilationUnit,
                              final FileObject fileObject,
                              final CompilerContext compilerContext) {
        final var phase1Resolver = new Phase1Resolver(
                compilerContext
        );
        compilationUnit.accept(phase1Resolver, null);

        compilationUnit.getClasses().stream()
                .findFirst().ifPresent(classDeclaration -> {
                    final var classSymbol = classDeclaration.getClassSymbol();
                    classSymbol.setMetaData(StandardElementMetaData.FILE_OBJECT, fileObject);
                });
    }

    public CompilationUnit resolvePhase2(final CompilationUnit compilationUnit,
                                         final CompilerContext compilerContext) {
        final var phase2Resolver = new Phase2Resolver(
                compilerContext
        );

        compilationUnit.accept(phase2Resolver, null);
        return compilationUnit;
    }

    private void transform(final CompilationUnit compilationUnit) {
        final var codeTransformers = applicationContext.getBeansOfType(CodeTransformer.class);
        codeTransformers.forEach(codeTransformer -> codeTransformer.tranform(compilationUnit));
    }

    private void check(final CompilationUnit compilationUnit) {
        final var checker = new Checker(errorCapturer);
        compilationUnit.accept(checker, null);
    }

    public CompilationUnit enchange(final CompilationUnit compilationUnit) {
        final var enhance = new Enhancer();
        compilationUnit.accept(enhance, null);
        return compilationUnit;
    }

    public CompilationUnit lower(final CompilationUnit compilationUnit, final CompilerContext compilerContext) {
        final var lower = new Lower(compilerContext);
        compilationUnit.accept(lower, null);
        return compilationUnit;
    }

    public void ir(final CompilationUnit compilationUnit) {
        final var translate = new Translate(classElementLoader.getTypes());
        compilationUnit.accept(translate, null);
    }

    public CompilationUnit lambdaToMethod(final CompilationUnit compilationUnit) {
        final var lamdaToMethod = new LambdaToMethod();
        compilationUnit.accept(lamdaToMethod, null);
        return compilationUnit;
    }

    @Override
    public void close() {
        classElementLoader.close();
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