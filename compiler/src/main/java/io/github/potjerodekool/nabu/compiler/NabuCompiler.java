package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.backend.CompileException;
import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.compiler.annotation.processing.*;
import io.github.potjerodekool.nabu.compiler.annotation.processing.java.element.ElementWrapperFactory;
import io.github.potjerodekool.nabu.compiler.ast.symbol.module.impl.Modules;
import io.github.potjerodekool.nabu.compiler.backend.asm.ASMBackend;
import io.github.potjerodekool.nabu.compiler.backend.ir.IrGeneratingVisitor;
import io.github.potjerodekool.nabu.compiler.backend.ir.Optimizer;
import io.github.potjerodekool.nabu.compiler.extension.PluginRegistry;
import io.github.potjerodekool.nabu.compiler.impl.AnnotatePhase;
import io.github.potjerodekool.nabu.compiler.impl.CompilerDiagnosticListener;
import io.github.potjerodekool.nabu.compiler.impl.LambdaToMethodPhase;
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

            final var allSourceKinds = compilerContext.getPluginRegistry()
                    .getLanguageParserManager()
                    .getSourceKinds();

            final var sourceFileKinds = allSourceKinds.toArray(FileObject.Kind[]::new);
            final var sourceFiles = resolveSourceFiles(fileManager, sourceFileKinds);
            final var compilationUnits = processFiles(sourceFiles, compilerContext);
            return generateCode(compilerContext, compilationUnits, fullOptions);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private int generateCode(final CompilerContextImpl compilerContext,
                             final List<CompilationUnit> compilationUnits,
                             final CompilerOptions compilerOptions) {
        final var backend = getBackendName(compilerOptions);

        if ("ASM".equals(backend)) {
            final var asmBackend = new ASMBackend();

            final var modules = compilationUnits.stream()
                    .map(cu -> {
                        final IrGeneratingVisitor visitor = new IrGeneratingVisitor();
                        visitor.acceptTree(cu, null);
                        return visitor.getModule();
                    }).toList();

            for (var module : modules) {
                try {
                    final var optimizedModule = Optimizer.optimize(module);
                    asmBackend.compile(optimizedModule, CompileOptions.defaults(), targetDirectory);
                } catch (CompileException e) {
                    return -1;
                }
            }
            return 0;
        } else {
            return -1;
        }
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
        var compilationUnits = parseFiles(files, compilerContext);

        Modules.getInstance(compilerContext)
                .initAllModules();

        compilationUnits = compilationUnits.stream()
                .map(fileObjectAndCompilationUnit ->
                        enterPhase(fileObjectAndCompilationUnit, compilerContext))
                .toList();

        runAnnotationProcessors(compilerContext, compilationUnits);

        final var allSources = new ArrayList<>(compilationUnits);

        compilationUnits = allSources.stream()
                .map(it -> resolvePhase(it, compilerContext))
                .map(it -> AnnotatePhase.annotate(it, compilerContext))
                .map(it -> transform(it, compilerContext))
                .map(it -> check(it, compilerContext, compilerDiagnosticListener))
                .toList();

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
        return files.stream()
                .map(file -> {
                    return parseFile(file, compilerContext);
                })
                .filter(Objects::nonNull)
                .toList();
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

    private void runAnnotationProcessors(final CompilerContextImpl compilerContext,
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
                classes = resolveClasses(roundResult);
                filer.prepareForRound();
            }
        } while (!generatedSourceFiles.isEmpty());
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