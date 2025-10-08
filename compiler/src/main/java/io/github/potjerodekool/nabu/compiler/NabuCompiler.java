package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.dependencyinjection.ApplicationContext;
import io.github.potjerodekool.dependencyinjection.ClassPathScanner;
import io.github.potjerodekool.nabu.compiler.backend.ByteCodePhase;
import io.github.potjerodekool.nabu.compiler.backend.IRPhase;
import io.github.potjerodekool.nabu.compiler.diagnostic.ConsoleDiagnosticListener;
import io.github.potjerodekool.nabu.compiler.diagnostic.DiagnosticListener;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.io.FileManager;
import io.github.potjerodekool.nabu.compiler.io.NabuCFileManager;
import io.github.potjerodekool.nabu.compiler.io.FileObject;
import io.github.potjerodekool.nabu.compiler.io.StandardLocation;
import io.github.potjerodekool.nabu.compiler.lang.support.nabu.NabuLanguageParser;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static io.github.potjerodekool.nabu.compiler.CheckPhase.check;
import static io.github.potjerodekool.nabu.compiler.EnterPhase.enterPhase;
import static io.github.potjerodekool.nabu.compiler.ResolvePhase.resolvePhase;
import static io.github.potjerodekool.nabu.compiler.TransformPhase.transform;
import static io.github.potjerodekool.nabu.compiler.backend.LowerPhase.lower;

public class NabuCompiler {

    private Path targetDirectory = Paths.get("output");

    private final ErrorCapture errorCapture = new ErrorCapture(
            new ConsoleDiagnosticListener()
    );

    private final ApplicationContext applicationContext = new ApplicationContext();

    private final ByteCodePhase byteCodePhase = new ByteCodePhase();

    public void setListener(final DiagnosticListener listener) {
        errorCapture.setListener(listener);
    }

    public int compile(final CompilerOptions compilerOptions) {
        try (final NabuCFileManager fileManager = new NabuCFileManager();
             final var compilerContext = new CompilerContextImpl(
                     applicationContext,
                     fileManager
             )) {
            setup(fileManager, compilerContext, compilerOptions);

            final var nabuSourceFiles = resolveSourceFiles(fileManager, new FileObject.Kind(".nabu", true));
            final var compilationUnits = processFiles(nabuSourceFiles, compilerContext);

            return byteCodePhase.generate(
                    compilationUnits,
                    compilerOptions,
                    errorCapture,
                    targetDirectory
            );
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

    private List<FileObject> resolveSourceFiles(final FileManager fileManager,
                                                final FileObject.Kind kind) {
        return fileManager.getFilesForLocation(
                StandardLocation.SOURCE_PATH,
                kind
        );
    }

    private void scanForBeans() {
        final var scanner = new ClassPathScanner();
        final var beans = scanner.scan();
        applicationContext.registerBeans(beans);
    }

    private List<CompilationUnit> processFiles(final List<FileObject> files,
                                               final CompilerContextImpl compilerContext) {
        var fileObjectAndCompilationUnits = parseFiles(files, compilerContext).stream()
                .map(it -> enterPhase(it, compilerContext))
                .toList();

        final var compilationUnits = fileObjectAndCompilationUnits.stream()
                .map(it -> resolvePhase(it, compilerContext))
                .map(it -> AnnotatePhase.annotate(it, compilerContext))
                .map(it -> transform(it, applicationContext))
                //.map(it -> resolvePhase(it, compilerContext))
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

    private List<FileObjectAndCompilationUnit> parseFiles(final List<FileObject> files,
                                                          final CompilerContext compilerContext) {
        return files.stream()
                .map(file -> new FileObjectAndCompilationUnit(file, parseFile(file, compilerContext)))
                .toList();
    }

    public CompilationUnit parseFile(final FileObject fileObject,
                                     final CompilerContext compilerContext) {
        return new NabuLanguageParser().parse(fileObject, compilerContext);
    }

}
