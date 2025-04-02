package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.dependencyinjection.ApplicationContext;
import io.github.potjerodekool.dependencyinjection.ClassPathScanner;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.backend.generate.asm.AsmdByteCodeGenerator;
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
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.io.FileManager;
import io.github.potjerodekool.nabu.compiler.io.NabuCFileManager;
import io.github.potjerodekool.nabu.compiler.io.FileObject;
import io.github.potjerodekool.nabu.compiler.io.StandardLocation;
import io.github.potjerodekool.nabu.compiler.log.LogLevel;
import io.github.potjerodekool.nabu.compiler.log.Logger;
import io.github.potjerodekool.nabu.compiler.resolve.*;
import io.github.potjerodekool.nabu.compiler.resolve.internal.EnterClasses;
import io.github.potjerodekool.nabu.compiler.resolve.internal.Phase2Resolver;
import io.github.potjerodekool.nabu.compiler.transform.CodeTransformer;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class NabuCompiler {

    private final Logger logger = Logger.getLogger(NabuCompiler.class.getName());

    private Path targetDirectory = Paths.get("output");

    private final NabuCompilerParser parser = new NabuCompilerParser();

    private final ErrorCapture errorCapture = new ErrorCapture(
            new ConsoleDiagnosticListener()
    );


    private final ApplicationContext applicationContext = new ApplicationContext();

    public void compile(final CompilerOptions compilerOptions) {
        try (final NabuCFileManager fileManager = new NabuCFileManager();
             final var compilerContext = new CompilerContextImpl(
                     applicationContext,
                     fileManager
             )) {
            setup(fileManager, compilerContext, compilerOptions);

            final var nabuSourceFiles = resolveSourceFiles(fileManager);
            final var compilationUnits = processFiles(nabuSourceFiles, compilerContext);

            final var exitCode = generate(
                    compilationUnits,
                    compilerOptions,
                    compilerContext
            );

            if (exitCode != 0) {
                System.exit(exitCode);
            }
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setup(final FileManager fileManager,
                       final CompilerContext compilerContext,
                       final CompilerOptions compilerOptions) {
        fileManager.processOptions(compilerOptions);

        compilerOptions.getTargetDirectory()
                .ifPresent(path -> this.targetDirectory = path);

        scanForBeans();

        applicationContext.registerBean(CompilerContextImpl.class, compilerContext);
    }

    private List<FileObject> resolveSourceFiles(final FileManager fileManager) {
        return fileManager.getFilesForLocation(
                StandardLocation.SOURCE_PATH,
                FileObject.Kind.SOURCE
        );
    }

    private int generate(final List<CompilationUnit> compilationUnits,
                         final CompilerOptions compilerOptions,
                         final CompilerContext compilerContext) {
        if (errorCapture.getErrorCount() > 0) {
            logger.log(LogLevel.ERROR,
                    "Compilation failed with " + errorCapture.getErrorCount() + " errors"
            );
            return 1;
        }

        compilationUnits.forEach(compilationUnit ->
                doGenerate(compilationUnit, compilerOptions, compilerContext));

        return 0;
    }

    private void doGenerate(final CompilationUnit compilationUnit,
                            final CompilerOptions compilerOptions,
                            final CompilerContext compilerContext) {
        final ByteCodeGenerator generator = new AsmdByteCodeGenerator(
                compilerContext.getElements(),
                compilerOptions
        );

        final var moduleDeclaration = compilationUnit.getModuleDeclaration();
        final var classes = compilationUnit.getClasses();
        final String packageName;
        final String name;

        if (moduleDeclaration != null) {
            generator.generate(moduleDeclaration, null);
            name = "module-info";
            packageName = null;
        } else if (!classes.isEmpty()) {
            final var clazz = classes.getFirst();
            final var packageDeclaration = compilationUnit.getPackageDeclaration();
            generator.generate(clazz, null);
            name = clazz.getSimpleName();
            packageName = packageDeclaration.getQualifiedName();
        } else {
            return;
        }

        final var bytecode = generator.getBytecode();
        final var outputPath = Path.of(name + ".class");

        Path outputDirectory;

        if (packageName != null) {
            final var packagePath = Paths.get(packageName
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
    }

    private void scanForBeans() {
        final var scanner = new ClassPathScanner();
        final var beans = scanner.scan();
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

    private List<CompilationUnit> processFiles(final List<FileObject> files,
                                               final CompilerContextImpl compilerContext) {
        var fileObjectAndCompilationUnits = parseFiles(files).stream()
                .map(this::enchange)
                .map(it -> resolvePhase1(it, compilerContext))
                .toList();

        final var compilationUnits = fileObjectAndCompilationUnits.stream().map(it -> resolvePhase2(it, compilerContext))
                .map(this::transform)
                .map(it -> resolvePhase2(it, compilerContext))
                .map(it -> check(it.compilationUnit()))
                .toList();

        if (errorCapture.getErrorCount() > 0) {
            return List.of();
        }

        return compilationUnits.stream()
                .map(this::lambdaToMethod)
                .map(cu -> lower(cu, compilerContext))
                .map(cu -> this.ir(cu, compilerContext))
                .toList();

    }

    private List<FileObjectAndCompilationUnit> parseFiles(final List<FileObject> files) {
        return files.stream()
                .map(file -> new FileObjectAndCompilationUnit(file, parseFile(file)))
                .toList();
    }

    public CompilationUnit parseFile(final FileObject fileObject) {
        logger.log(LogLevel.INFO, "Parsing " + fileObject.getFileName());

        try (var inputStream = fileObject.openInputStream()) {
            final var compilationUnitContext = parser.parse(inputStream);
            final var visitor = new NabuCompilerVisitor(fileObject);
            return (CompilationUnit) compilationUnitContext.accept(visitor);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    private FileObjectAndCompilationUnit resolvePhase1(final FileObjectAndCompilationUnit fileObjectAndCompilationUnit,
                                                       final CompilerContextImpl compilerContext) {
        final var fileObject = fileObjectAndCompilationUnit.fileObject();
        final var compilationUnit = fileObjectAndCompilationUnit.compilationUnit();

        final var phase1Resolver = new EnterClasses(
                compilerContext
        );
        compilationUnit.accept(phase1Resolver, null);

        compilationUnit.getClasses().stream()
                .map(ClassDeclaration::getClassSymbol)
                .map(classSymbol -> (ClassSymbol) classSymbol)
                .findFirst().ifPresent(classSymbol -> classSymbol.setSourceFile(fileObject));
        return fileObjectAndCompilationUnit;
    }

    private FileObjectAndCompilationUnit resolvePhase2(final FileObjectAndCompilationUnit fileObjectAndCompilationUnit,
                                                       final CompilerContextImpl compilerContext) {
        final var compilationUnit = fileObjectAndCompilationUnit.compilationUnit();

        final var phase2Resolver = new Phase2Resolver(
                compilerContext
        );

        compilationUnit.accept(phase2Resolver, null);
        return fileObjectAndCompilationUnit;
    }

    private FileObjectAndCompilationUnit transform(final FileObjectAndCompilationUnit fileObjectAndCompilationUnit) {
        final var compilationUnit = fileObjectAndCompilationUnit.compilationUnit();
        final var codeTransformers = applicationContext.getBeansOfType(CodeTransformer.class);
        codeTransformers.forEach(codeTransformer -> codeTransformer.tranform(compilationUnit));
        return fileObjectAndCompilationUnit;
    }

    private CompilationUnit check(final CompilationUnit compilationUnit) {
        final var checker = new Checker(errorCapture);
        compilationUnit.accept(checker, null);
        return compilationUnit;
    }

    private FileObjectAndCompilationUnit enchange(final FileObjectAndCompilationUnit fileObjectAndCompilationUnit) {
        final var compilationUnit = fileObjectAndCompilationUnit.compilationUnit();
        final var enhance = new Enhancer();
        compilationUnit.accept(enhance, null);
        return fileObjectAndCompilationUnit;
    }

    private CompilationUnit lower(final CompilationUnit compilationUnit,
                                  final CompilerContextImpl compilerContext) {
        final var lower = new Lower(compilerContext);
        compilationUnit.accept(lower, null);
        return compilationUnit;
    }

    private CompilationUnit ir(final CompilationUnit compilationUnit,
                               final CompilerContextImpl compilerContext) {
        final var translate = new Translate(compilerContext.getClassElementLoader().getTypes());
        compilationUnit.accept(translate, null);
        return compilationUnit;
    }

    private CompilationUnit lambdaToMethod(final CompilationUnit compilationUnit) {
        final var lamdaToMethod = new LambdaToMethod();
        compilationUnit.accept(lamdaToMethod, null);
        return compilationUnit;
    }

}

class ErrorCapture extends DelegateDiagnosticListener {

    private final List<Diagnostic> errors = new ArrayList<>();

    public ErrorCapture(final DiagnosticListener delegate) {
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

record FileObjectAndCompilationUnit(FileObject fileObject,
                                    CompilationUnit compilationUnit) {

}