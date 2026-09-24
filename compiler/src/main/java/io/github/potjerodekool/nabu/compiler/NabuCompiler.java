package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.compiler.annotation.processing.*;
import io.github.potjerodekool.nabu.compiler.annotation.processing.java.element.ElementWrapperFactory;
import io.github.potjerodekool.nabu.compiler.ast.symbol.module.impl.Modules;
import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.IrGeneratingVisitor;
import io.github.potjerodekool.nabu.backend.ir.Optimizer;
import io.github.potjerodekool.nabu.backend.ir.SsaBuilder;
import io.github.potjerodekool.nabu.compiler.extension.BackendManager;
import io.github.potjerodekool.nabu.compiler.extension.PluginRegistry;
import io.github.potjerodekool.nabu.compiler.impl.AnnotatePhase;
import io.github.potjerodekool.nabu.compiler.impl.CompilerDiagnosticListener;
import io.github.potjerodekool.nabu.compiler.impl.LambdaToMethodPhase;
import io.github.potjerodekool.nabu.compiler.incremental.IncrementalBuildState;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;
import io.github.potjerodekool.nabu.tools.*;
import io.github.potjerodekool.nabu.compiler.impl.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.io.impl.NabuCFileManager;
import io.github.potjerodekool.nabu.tools.diagnostic.ConsoleDiagnosticListener;
import io.github.potjerodekool.nabu.tools.diagnostic.DefaultDiagnostic;
import io.github.potjerodekool.nabu.tools.diagnostic.Diagnostic;
import io.github.potjerodekool.nabu.tools.diagnostic.DiagnosticListener;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.util.CompileException;

import javax.annotation.processing.Processor;
import javax.lang.model.element.TypeElement;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static io.github.potjerodekool.nabu.compiler.backend.lower.Lower.lower;
import static io.github.potjerodekool.nabu.compiler.impl.CheckPhase.check;
import static io.github.potjerodekool.nabu.compiler.impl.EnterPhase.enterPhase;
import static io.github.potjerodekool.nabu.compiler.resolve.impl.ResolverPhase.resolvePhase;
import static io.github.potjerodekool.nabu.compiler.impl.TransformPhase.transform;

public class NabuCompiler implements Compiler {

    private Path targetDirectory = Paths.get("output");
    private final CompilerDiagnosticListener compilerDiagnosticListener = new CompilerDiagnosticListener(new ConsoleDiagnosticListener());
    private ByteCodeGeneratorListener byteCodeGeneratorListener = DevNullByteCodeGeneratorListener.INSTANCE;

    public void setListener(final DiagnosticListener listener) {
        compilerDiagnosticListener.setListener(listener);
    }

    public void setByteCodeGeneratorListener(final ByteCodeGeneratorListener byteCodeGeneratorListener) {
        this.byteCodeGeneratorListener = byteCodeGeneratorListener;
    }

    @Override
    public int compile(final CompilerOptions compilerOptions) {
        try (final var compilerContext = configure(compilerOptions)) {
            final var fullOptions = compilerContext.getCompilerOptions();

            final var fileManager = compilerContext.getFileManager();

            final var allSourceKinds = new ArrayList<FileObject.Kind>();
            allSourceKinds.addAll(compilerContext.getPluginRegistry()
                    .getLanguageParserManager()
                    .getSourceKinds());
            allSourceKinds.addAll(compilerContext.getPluginRegistry()
                    .getLanguageSupportManager()
                    .getSourceKinds());

            final var sourceFileKinds = allSourceKinds.toArray(FileObject.Kind[]::new);
            final var sourceFiles = resolveSourceFiles(fileManager, sourceFileKinds);

            final var incremental = isIncremental(fullOptions);
            Path incrementalStateFile = null;
            IncrementalBuildState previousState = null;

            if (incremental) {
                incrementalStateFile = IncrementalBuildState.stateFile(targetDirectory);

                if (IncrementalBuildState.isUpToDate(incrementalStateFile, fullOptions, sourceFiles)) {
                    compilerDiagnosticListener.report(new DefaultDiagnostic(
                            Diagnostic.Kind.NOTE,
                            "Incremental compilation skipped: all sources are up to date.",
                            null
                    ));
                    return 0;
                }

                previousState = IncrementalBuildState.read(incrementalStateFile);
            }

            final var compilationUnits = processFiles(sourceFiles, compilerContext);

            if (compilationUnits.isEmpty()
                    && compilerDiagnosticListener.getErrorCount() > 0) {
                // Er zijn fouten gerapporteerd waardoor geen enkele
                // compilatie-unit overbleef: signaal een falende build.
                return -1;
            }

            final Map<String, List<String>> producedBySource = incremental ? new HashMap<>() : null;
            final var result = generateCode(compilerContext, compilationUnits, fullOptions, producedBySource);

            if (incremental && result == 0) {
                IncrementalBuildState.write(
                        incrementalStateFile,
                        IncrementalBuildState.configFingerprint(fullOptions),
                        producedBySource,
                        sourceFiles
                );
                IncrementalBuildState.deleteStaleClasses(
                        targetDirectory,
                        previousState,
                        producedBySource.values()
                );
            }

            return result;
        } catch (final Exception e) {
            e.printStackTrace(System.err);
            throw new RuntimeException(e);
        }
    }

    private int generateCode(final CompilerContextImpl compilerContext,
                             final List<CompilationUnit> compilationUnits,
                             final CompilerOptions compilerOptions,
                             final Map<String, List<String>> producedBySource) throws CompileException {
        final var backend = getBackendName(compilerOptions);
        final var targetVersion = compilerOptions.getTargetVersion();

        final var codeBackend = BackendManager.createBackend(
                backend,
                compilerContext.getPluginRegistry(),
                compilerContext
        );

        if (codeBackend != null) {
            // Batch-compilatie over álle compilatie-eenheden (niet per CU):
            // object-model relaties over klassen heen (super-velden,
            // override-slots, super-constructor-ketens) moeten ook over
            // bronbestanden heen in één backend-run worden opgelost.
            final var compileOptions = CompileOptions.defaults().withJavaVersion(targetVersion);
            final var allModules = new ArrayList<io.github.potjerodekool.nabu.backend.ir.IRModule>();

            for (final var compilationUnit : compilationUnits) {
                final String sourceName = producedBySource != null
                        ? IncrementalBuildState.normalizePath(compilationUnit.getFileObject().getFileName())
                        : null;

                final IrGeneratingVisitor visitor = new IrGeneratingVisitor();
                visitor.acceptTree(compilationUnit, null);

                for (final var module : visitor.getModules()) {
                    // Type-inferentie vóór SSA (per module)
                for (final var fn : module.functions()) {
                    if (!fn.isExternal()) {
                        new io.github.potjerodekool.nabu.backend.ir.optimize.TypeInference().run(fn);
                    }
                }
                    SsaBuilder.run(module);

                    final var optimized = Optimizer.optimize(module);

                    allModules.add(optimized);

                    if (producedBySource != null) {
                        producedBySource.computeIfAbsent(sourceName, k -> new ArrayList<>())
                                .add(optimized.name.replace('.', '/') + ".class");
                    }
                }
            }

            try {
                codeBackend.compileAll(allModules, compileOptions, targetDirectory);
            } catch (final CompileException e) {
                compilerDiagnosticListener.report(new DefaultDiagnostic(
                        Diagnostic.Kind.ERROR,
                        e.getMessage() != null ? e.getMessage() : "Backend fout",
                        null
                ));
                return -1;
            }
            return 0;
        } else {
            return -1;
        }
    }

    private boolean isIncremental(final CompilerOptions compilerOptions) {
        return compilerOptions.getClassOutput().isPresent()
                && compilerOptions.getOption(CompilerOption.INCREMENTAL, false, Boolean.class);
    }

    private String getBackendName(final CompilerOptions compilerOptions) {
        return compilerOptions.getOption(CompilerOption.BACKEND)
                .orElse("ASM");
    }

    @Override
    public CompilerContextImpl configure(final CompilerOptions compilerOptions) {
        final var fullOptions = withDefaults(compilerOptions);

        final var compilerContext = new CompilerContextImpl(
                new NabuCFileManager(),
                fullOptions,
                new PluginRegistry(),
                AsmClassElementLoader::new
        );
        final var fileManager = compilerContext.getFileManager();
        setup(fileManager, fullOptions);

        return compilerContext;
    }

    private CompilerOptions withDefaults(final CompilerOptions compilerOptions) {
        final var original = (CompilerOptions.CompilerOptionsImpl) compilerOptions;
        final var newOptions = new HashMap<>(original.options());

        if (!original.hasOption(CompilerOption.SOURCE_OUTPUT)) {
            original.getOption(CompilerOption.CLASS_OUTPUT).ifPresent(optionValue ->
                    newOptions.put(CompilerOption.SOURCE_OUTPUT, optionValue));
        }

        return new CompilerOptions.CompilerOptionsImpl(newOptions);
    }

    private void setup(final NabuCFileManager fileManager,
                       final CompilerOptions compilerOptions) {
        fileManager.processOptions(compilerOptions);

        compilerOptions.getClassOutput()
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
                                               final CompilerContextImpl compilerContext) {
        var compilationUnits = new ArrayList<>(parseFiles(files, compilerContext));

        compilationUnits = compilationUnits.stream()
                .map(fileObjectAndCompilationUnit ->
                        enterPhase(fileObjectAndCompilationUnit, compilerContext))
                .collect(Collectors.toCollection(ArrayList::new));

        // Import scopes vroegtijdig vullen (vóór lazy klassymbol-completion
        // en vóór de resolutie): anders kan de volgorde van completies de
        // star-import definities 'verbruiken'.
        compilationUnits.forEach(unit ->
                compilerContext.getTypeEnter().fillImportsForUnit(unit));

        Modules.getInstance(compilerContext)
                .initAllModules();

        final var generatedUnits = runAnnotationProcessors(compilerContext, compilationUnits);

        // Maximaal één compilatie-eenheid per bronbestand. Een gegenereerd
        // .java-bestand dat al op de source-path staat (bijv. wanneer build-
        // helper gegenereerde bronnen als sourceroot toevoegt) mag niet als
        // aparte eenheid worden toegevoegd — anders wordt dezelfde klasse
        // tweemaal binnengevoegd (dubbele member-registratie, verloren
        // gesynthetiseerde constructor, dubbel gegenereerde bytecode).
        final var sourcePaths = compilationUnits.stream()
                .map(CompilationUnit::getFileObject)
                .map(FileObject::getFileName)
                .map(IncrementalBuildState::normalizePath)
                .collect(Collectors.toSet());

        generatedUnits.removeIf(generatedUnit ->
                sourcePaths.contains(
                        IncrementalBuildState.normalizePath(generatedUnit.getFileObject().getFileName())
                ));

        compilationUnits.addAll(generatedUnits);

        compilationUnits = compilationUnits.stream()
                .map(it -> resolvePhase(it, compilerContext))
                .map(it -> AnnotatePhase.annotate(it, compilerContext))
                .map(it -> transform(it, compilerContext))
                .map(it -> check(it, compilerContext, compilerDiagnosticListener))
                .collect(Collectors.toCollection(ArrayList::new));

        if (compilerDiagnosticListener.getErrorCount() > 0) {
            return List.of();
        }

        return compilationUnits.stream()
                .map(LambdaToMethodPhase::lambdaToMethod)
                .map(cu -> lower(cu, compilerContext))
                //.map(cu -> IRPhase.ir(compilerContext, cu))
                .toList();
    }

    private List<CompilationUnit> parseAndEnter(final List<? extends FileObject> files,
                                                final CompilerContextImpl compilerContext) {
        return parseFiles(files, compilerContext).stream()
                .map(it -> enterPhase(it, compilerContext))
                .toList();
    }

    private List<CompilationUnit> parseFiles(final List<? extends FileObject> files,
                                             final CompilerContextImpl compilerContext) {
        final var result = new ArrayList<CompilationUnit>();
        for (final var file : files) {
            final var unit = parseFile(file, compilerContext);
            if (unit != null) {
                result.add(unit);
            }
        }
        return result;
    }

    private CompilationUnit parseFile(final FileObject fileObject,
                                      final CompilerContextImpl compilerContext) {
        final var sourceParserOptional = compilerContext.getPluginRegistry()
                .getSourceParser(fileObject.getKind());

        if (sourceParserOptional.isPresent()) {
            final var parser = sourceParserOptional.get();
            return parser.parse(fileObject, compilerContext);
        } else {
            compilerDiagnosticListener.report(new DefaultDiagnostic(
                    Diagnostic.Kind.ERROR,
                    "No parser found to parse file",
                    fileObject
            ));
            return null;
        }
    }

    private List<CompilationUnit> runAnnotationProcessors(final CompilerContextImpl compilerContext,
                                                          final List<CompilationUnit> compilationUnits) {
        Set<TypeElement> classes = resolveClasses(compilationUnits);
        final var processors = findAnnotationProcessors(compilerContext);
        final var processingEnvironment = createProcessingEnvironment(compilerContext);
        final var filer = processingEnvironment.getFiler();

        processors.forEach(processor -> processor.init(processingEnvironment));
        final var processorStates = processors.stream()
                .map(processor -> createProcessorState(
                        processor,
                        compilerContext
                ))
                .toList();

        Set<String> generatedSourceFiles;
        List<CompilationUnit> roundResult;
        final List<CompilationUnit> generatedUnits = new ArrayList<>();

        do {
            processingEnvironment.round(classes, processorStates);
            generatedSourceFiles = filer.getGeneratedSourceFiles();

            if (!generatedSourceFiles.isEmpty()) {
                final var fileObjects = generatedSourceFiles.stream()
                        .map(fileName -> new PathFileObject(
                                new FileObject.Kind(".java", true),
                                Paths.get(fileName)
                        ))
                        .toList();

                roundResult = parseAndEnter(fileObjects, compilerContext);
                generatedUnits.addAll(roundResult);
                classes = resolveClasses(roundResult);
                filer.prepareForRound();
            }
        } while (!generatedSourceFiles.isEmpty());

        processingEnvironment.round(Set.of(), processorStates, true);

        return generatedUnits;
    }

    private JavacProcessingEnvironment createProcessingEnvironment(final CompilerContextImpl compilerContext) {
        final var symbolTable = compilerContext.getSymbolTable();
        final var elements = compilerContext.getElements();
        final var fileManager = compilerContext.getFileManager();
        final var javacElements = new JavacElements(compilerContext.getElements());
        final var types = new JavacTypes(compilerContext.getTypes());
        final var messager = new JavacMessager(compilerDiagnosticListener, elements);
        final var filer = new JavacFiler(symbolTable, elements, fileManager);
        final var options = compilerContext.getCompilerOptions();
        return new JavacProcessingEnvironment(messager, filer, javacElements, types, options);
    }

    private Set<TypeElement> resolveClasses(final List<CompilationUnit> compilationUnits) {
        return compilationUnits.stream()
                .flatMap(unit -> unit.getClasses().stream())
                .map(ClassDeclaration::getClassSymbol)
                .map(classSymbol -> (TypeElement) ElementWrapperFactory.wrap(classSymbol))
                .collect(Collectors.toSet());
    }

    private ProcessorState createProcessorState(final Processor processor,
                                                final CompilerContextImpl compilerContext) {
        final var loader = compilerContext.getClassElementLoader();
        final var annotations = processor.getSupportedAnnotationTypes().stream()
                .map(className -> loader.loadClass(null, className))
                .filter(Objects::nonNull)
                .map(ElementWrapperFactory::wrap)
                .map(clazz -> (TypeElement) clazz)
                .collect(Collectors.toSet());

        return new ProcessorState(processor, annotations);
    }

    private List<Processor> findAnnotationProcessors(final CompilerContextImpl compilerContext) {
        final var fileManager = compilerContext.getFileManager();
        final ClassLoader classLoader;

        if (fileManager.hasLocation(StandardLocation.ANNOTATION_PROCESSOR_PATH)) {
            classLoader = fileManager.getClassLoader(StandardLocation.ANNOTATION_PROCESSOR_PATH);
        } else {
            classLoader = fileManager.getClassLoader(StandardLocation.CLASS_PATH);
        }

        io.github.potjerodekool.nabu.compiler.ast.symbol.impl.AnnotationUtils
                .setAnnotationProcessorClassLoader(classLoader);

        return ServiceLoader.load(Processor.class, classLoader).stream()
                .map(ServiceLoader.Provider::get)
                .peek(processor -> compilerDiagnosticListener.report(new DefaultDiagnostic(
                        Diagnostic.Kind.NOTE,
                        String.format("Found annotation processor %s", processor.getClass().getName()),
                        null
                )))
                .toList();
    }

}

class DevNullByteCodeGeneratorListener implements ByteCodeGeneratorListener {
    static final DevNullByteCodeGeneratorListener INSTANCE = new DevNullByteCodeGeneratorListener();

    private DevNullByteCodeGeneratorListener() {
    }

    @Override
    public void generated(final FileObject sourceFile,
                          final PathFileObject classFile,
                          final String className) {

    }
}