package io.github.potjerodekool.nabu.compiler.impl;

import io.github.potjerodekool.nabu.compiler.extension.PluginRegistry;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;
import io.github.potjerodekool.nabu.lang.model.element.ModuleElement;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import io.github.potjerodekool.nabu.compiler.backend.ByteCodePhase;
import io.github.potjerodekool.nabu.compiler.backend.IRPhase;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.tools.FileManager;
import io.github.potjerodekool.nabu.compiler.io.impl.NabuCFileManager;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tools.StandardLocation;
import io.github.potjerodekool.nabu.compiler.lang.support.nabu.NabuLanguageParser;
import io.github.potjerodekool.nabu.tools.diagnostic.ConsoleDiagnosticListener;
import io.github.potjerodekool.nabu.tools.diagnostic.DiagnosticListener;
import io.github.potjerodekool.nabu.tree.CompilationUnit;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static io.github.potjerodekool.nabu.compiler.impl.CheckPhase.check;
import static io.github.potjerodekool.nabu.compiler.impl.EnterPhase.enterPhase;
import static io.github.potjerodekool.nabu.compiler.impl.ResolvePhase.resolvePhase;
import static io.github.potjerodekool.nabu.compiler.impl.TransformPhase.transform;
import static io.github.potjerodekool.nabu.compiler.backend.LowerPhase.lower;

public class NabuCompiler {

    private Path targetDirectory = Paths.get("output");

    private final ErrorCapture errorCapture = new ErrorCapture(
            new ConsoleDiagnosticListener()
    );

    private final ByteCodePhase byteCodePhase = new ByteCodePhase();

    public void setListener(final DiagnosticListener listener) {
        errorCapture.setListener(listener);
    }

    public int compile(final CompilerOptions compilerOptions) {
        final var pluginRegistry = new PluginRegistry();
        try (final NabuCFileManager fileManager = new NabuCFileManager();
             final var compilerContext = new CompilerContextImpl(
                     fileManager,
                     pluginRegistry,
                     AsmClassElementLoader::new
             )) {
            setup(fileManager, compilerOptions);

            final var sourceFileKinds = getSourceFileKinds(compilerOptions);

            final var nabuSourceFiles = resolveSourceFiles(fileManager, sourceFileKinds);
            final var compilationUnits = processFiles(nabuSourceFiles, null, compilerContext);

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

    private FileObject.Kind[] getSourceFileKinds(final CompilerOptions compilerOptions) {
        final var extensions = compilerOptions.getSourceFileExtensions();

        if (extensions.isEmpty()) {
            return new FileObject.Kind[]{
                    new FileObject.Kind(".nabu", true)
            };
        }

        return compilerOptions.getSourceFileExtensions()
                .stream()
                .map(extension -> new FileObject.Kind(extension, true))
                .toArray(FileObject.Kind[]::new);
    }

    private void setup(final NabuCFileManager fileManager,
                       final CompilerOptions compilerOptions) {
        fileManager.processOptions(compilerOptions);

        compilerOptions.getTargetDirectory()
                .ifPresent(path -> this.targetDirectory = path);

    }

    private List<FileObject> resolveSourceFiles(final FileManager fileManager,
                                                final FileObject.Kind... kind) {
        return fileManager.getFilesForLocation(
                StandardLocation.SOURCE_PATH,
                kind
        );
    }

    private List<CompilationUnit> processFiles(final List<FileObject> files,
                                               final ModuleElement moduleElement,
                                               final CompilerContextImpl compilerContext) {
        var fileObjectAndCompilationUnits = parseFiles(files, moduleElement, compilerContext).stream()
                .map(it -> enterPhase(it, compilerContext))
                .toList();

        final var compilationUnits = fileObjectAndCompilationUnits.stream()
                .map(it -> resolvePhase(it, compilerContext))
                .map(it -> AnnotatePhase.annotate(it, compilerContext))
                .map(it -> transform(it, compilerContext))
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
                                                          final ModuleElement moduleElement,
                                                          final CompilerContext compilerContext) {
        return files.stream()
                .map(file -> new FileObjectAndCompilationUnit(file, parseFile(file, moduleElement, compilerContext)))
                .toList();
    }

    public CompilationUnit parseFile(final FileObject fileObject,
                                     final ModuleElement moduleElement,
                                     final CompilerContext compilerContext) {
        return new NabuLanguageParser().parse(fileObject, moduleElement, compilerContext);
    }

}
