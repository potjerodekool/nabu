package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.dependencyinjection.ApplicationContext;
import io.github.potjerodekool.dependencyinjection.ClassPathScanner;
import io.github.potjerodekool.nabu.compiler.backend.ByteCodePhase;
import io.github.potjerodekool.nabu.compiler.backend.IRPhase;
import io.github.potjerodekool.nabu.compiler.diagnostic.ConsoleDiagnosticListener;
import io.github.potjerodekool.nabu.compiler.frontend.parser.nabu.NabuCompilerParser;
import io.github.potjerodekool.nabu.compiler.frontend.parser.nabu.NabuCompilerVisitor;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.io.FileManager;
import io.github.potjerodekool.nabu.compiler.io.NabuCFileManager;
import io.github.potjerodekool.nabu.compiler.io.FileObject;
import io.github.potjerodekool.nabu.compiler.io.StandardLocation;
import io.github.potjerodekool.nabu.compiler.log.LogLevel;
import io.github.potjerodekool.nabu.compiler.log.Logger;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static io.github.potjerodekool.nabu.compiler.CheckPhase.check;
import static io.github.potjerodekool.nabu.compiler.EnterPhase.enterPhase;
import static io.github.potjerodekool.nabu.compiler.backend.LowerPhase.lower;
import static io.github.potjerodekool.nabu.compiler.ResolvePhase.resolvePhase;
import static io.github.potjerodekool.nabu.compiler.TransformPhase.transform;

public class NabuCompiler {

    private final Logger logger = Logger.getLogger(NabuCompiler.class.getName());

    private Path targetDirectory = Paths.get("output");

    private final ErrorCapture errorCapture = new ErrorCapture(
            new ConsoleDiagnosticListener()
    );


    private final ApplicationContext applicationContext = new ApplicationContext();

    private final ByteCodePhase byteCodePhase = new ByteCodePhase();

    public void compile(final CompilerOptions compilerOptions) {
        try (final NabuCFileManager fileManager = new NabuCFileManager();
             final var compilerContext = new CompilerContextImpl(
                     applicationContext,
                     fileManager
             )) {
            setup(fileManager, compilerContext, compilerOptions);

            final var nabuSourceFiles = resolveSourceFiles(fileManager);
            final var compilationUnits = processFiles(nabuSourceFiles, compilerContext);

            final var exitCode = byteCodePhase.generate(
                    compilationUnits,
                    compilerOptions,
                    errorCapture,
                    targetDirectory
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
                FileObject.Kind.SOURCE_NABU
        );
    }

    private void scanForBeans() {
        final var scanner = new ClassPathScanner();
        final var beans = scanner.scan();
        applicationContext.registerBeans(beans);
    }

    private List<CompilationUnit> processFiles(final List<FileObject> files,
                                               final CompilerContextImpl compilerContext) {
        var fileObjectAndCompilationUnits = parseFiles(files).stream()
                .map(it -> enterPhase(it, compilerContext))
                .toList();

        final var compilationUnits = fileObjectAndCompilationUnits.stream()
                .map(it -> resolvePhase(it, compilerContext))
                .map(it -> transform(it,applicationContext))
                .map(it -> resolvePhase(it, compilerContext))
                .map(it -> check(it.compilationUnit(), errorCapture))
                .toList();

        if (errorCapture.getErrorCount() > 0) {
            return List.of();
        }

        return compilationUnits.stream()
                .map(LambdaToMethodPhase::lambdaToMethod)
                .map(cu -> lower(cu, compilerContext))
                .map(cu -> IRPhase.ir(compilerContext, cu))
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
            final var compilationUnitContext = NabuCompilerParser.parse(inputStream);
            final var visitor = new NabuCompilerVisitor(fileObject);
            return (CompilationUnit) compilationUnitContext.accept(visitor);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

}
