package io.github.potjerodekool.nabu.compiler.annotation.processing;

import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.resolve.impl.SymbolTable;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.builder.AnnotationBuilder;
import io.github.potjerodekool.nabu.test.AbstractAnnotationProcessorTest;
import io.github.potjerodekool.nabu.testing.TreePrinter;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeKind;
import jakarta.inject.Inject;
import jakarta.persistence.Entity;
import org.hibernate.processor.HibernateProcessor;
import org.junit.jupiter.api.Test;
import org.mapstruct.Mapper;
import org.mapstruct.ap.MappingProcessor;

import javax.annotation.processing.Processor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavacProcessingEnvironmentTest extends AbstractAnnotationProcessorTest {

    @Override
    protected void configureOptions(final CompilerOptions.CompilerOptionsBuilder optionsBuilder) {
        super.configureOptions(optionsBuilder);
        optionsBuilder.option(CompilerOption.SOURCE_OUTPUT, SOURCE_OUTPUT_PATH);
    }

    @Override
    protected String getClassPath() {
        return createClassPath(
                getLocationOfClass(Inject.class), //JPA
                getLocationOfClass(Entity.class), //JPA
                getLocationOfClass(Mapper.class)//, //Mapstruct
        );
    }

    @Test
    void testHibernateModelGen() {
        final var personClass = createPersonClass();
        process(Set.of(personClass), List.of(new HibernateProcessor()));

        assertGeneratedSourceFileExists("Person_.java");
        final var unit = parseJavaSource("Person_.java");
        final var actual = TreePrinter.print(unit);
        final var expected = loadResource("JavacProcessingEnvironmentTest/Person_.java");
        assertEquals(expected, actual);
    }

    private ClassSymbol createPersonClass() {
        final var context = getCompilerContext();

        final var loader = context.getClassElementLoader();
        final var types = context.getTypes();
        final var symbolTable = context.getSymbolTable();

        final var object = loader.loadClass(null, "java.lang.Object");
        final var stringType = loader.loadClass(null, "java.lang.String").asType();
        final var longType = types.getPrimitiveType(TypeKind.LONG);
        final var setClass = loader.loadClass(null, "java.util.Set");
        final var stringSetType = types.getDeclaredType(setClass, stringType);
        final var elementCollectionType = (DeclaredType) loader.loadClass(null, "jakarta.persistence.ElementCollection").asType();
        final var module = (ModuleSymbol) context.getModules().getNoModule();
        final var entityClass = loader.loadClass(null, "jakarta.persistence.Entity");

        final var defaultPackage = symbolTable.lookupPackage(module, "");

        return new ClassSymbolBuilder()
                .annotations(
                        AnnotationBuilder.createAnnotation(
                                (DeclaredType) entityClass.asType(),
                                Collections.emptyMap()
                        )
                )
                .simpleName("Person")
                .kind(ElementKind.CLASS)
                .superclass(object.asType())
                .enclosingElement(defaultPackage)
                .enclosedElement(
                        new VariableSymbolBuilderImpl()
                                .kind(ElementKind.FIELD)
                                .type(longType)
                                .simpleName("id")
                                .build()
                )
                .enclosedElement(
                        new VariableSymbolBuilderImpl()
                                .kind(ElementKind.FIELD)
                                .type(stringType)
                                .simpleName("firstName")
                                .build()
                )
                .enclosedElement(
                        new VariableSymbolBuilderImpl()
                                .kind(ElementKind.FIELD)
                                .type(stringSetType)
                                .simpleName("tags")
                                .annotations(
                                        AnnotationBuilder.createAnnotation(
                                                elementCollectionType,
                                                Collections.emptyMap()
                                        )
                                )
                                .build()
                )
                .build();
    }

    private ClassSymbol createPersonDtoClass() {
        final var context = getCompilerContext();

        final var loader = context.getClassElementLoader();
        final var types = context.getTypes();
        final var symbolTable = context.getSymbolTable();

        final var object = loader.loadClass(null, "java.lang.Object");
        final var stringType = loader.loadClass(null, "java.lang.String").asType();
        final var longType = types.getPrimitiveType(TypeKind.LONG);
        final var setClass = loader.loadClass(null, "java.util.Set");
        final var stringSetType = types.getDeclaredType(setClass, stringType);
        final var module = (ModuleSymbol) context.getModules().getNoModule();
        final var entityClass = loader.loadClass(null, "jakarta.persistence.Entity");

        final var defaultPackage = symbolTable.lookupPackage(module, "");

        return new ClassSymbolBuilder()
                .annotations(
                        AnnotationBuilder.createAnnotation(
                                (DeclaredType) entityClass.asType(),
                                Collections.emptyMap()
                        )
                )
                .simpleName("PersonDto")
                .kind(ElementKind.CLASS)
                .superclass(object.asType())
                .enclosingElement(defaultPackage)
                .enclosedElement(
                        new VariableSymbolBuilderImpl()
                                .kind(ElementKind.FIELD)
                                .type(longType)
                                .simpleName("id")
                                .build()
                )
                .enclosedElement(
                        new VariableSymbolBuilderImpl()
                                .kind(ElementKind.FIELD)
                                .type(stringType)
                                .simpleName("firstName")
                                .build()
                )
                .enclosedElement(
                        new VariableSymbolBuilderImpl()
                                .kind(ElementKind.FIELD)
                                .type(stringSetType)
                                .simpleName("tags")
                                .build()
                )
                .build();
    }

    @Test
    void testMapStruct() {
        final var symbolTable = getCompilerContext().getSymbolTable();
        final var loader = getCompilerContext().getClassElementLoader();
        final var mapperClass = loader.loadClass(null, "org.mapstruct.Mapper");
        final var module = SymbolTable.NO_MODULE;
        final var defaultPackage = symbolTable.lookupPackage(module, "");

        final var personClass = createPersonClass();
        final var personDtoClass = createPersonDtoClass();

        final var elementBuilders = getCompilerContext().getElementBuilders();

        final var toDtoMethod = elementBuilders.executableElementBuilder()
                .kind(ElementKind.METHOD)
                .returnType(personDtoClass.asType())
                .simpleName("toDto")
                .parameter(
                        elementBuilders.variableElementBuilder()
                                .kind(ElementKind.PARAMETER)
                                .type(personClass.asType())
                                .simpleName("Person")
                                .build()
                ).build();

        final var personMapper = new ClassSymbolBuilder()
                .simpleName("PersonMapper")
                .kind(ElementKind.INTERFACE)
                .enclosingElement(defaultPackage)
                .annotations(
                        AnnotationBuilder.createAnnotation(
                                (DeclaredType) mapperClass.asType(),
                                Collections.emptyMap()
                        )
                )
                .enclosedElement(toDtoMethod)
                .build();


        process(
                Set.of(personMapper),
                List.of(new MappingProcessor())
        );
    }

    @Test
    void testLombok() throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
        final var clazz = getClass().getClassLoader().loadClass("lombok.launch.AnnotationProcessorHider");
        final var processorClass = Arrays.stream(clazz.getDeclaredClasses())
                        .filter(it -> "AnnotationProcessor".equals(it.getSimpleName()))
                                .findFirst()
                                        .orElse(null);
        final var processor = (Processor) processorClass.getConstructor().newInstance();

        System.out.println(clazz);

        //lombok.launch.AnnotationProcessorHider
                //.AnnotationProcessor.

        process(
                Set.of(),
                List.of(processor)
        );
    }

}