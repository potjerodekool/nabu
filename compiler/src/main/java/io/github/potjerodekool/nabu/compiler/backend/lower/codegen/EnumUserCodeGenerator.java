package io.github.potjerodekool.nabu.compiler.backend.lower.codegen;

import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.VariableSymbol;
import io.github.potjerodekool.nabu.compiler.resolve.impl.SymbolTable;
import io.github.potjerodekool.nabu.resolve.scope.WritableScope;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.PackageSymbol;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.tree.TreeFilter;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.element.builder.ClassDeclarationBuilder;
import io.github.potjerodekool.nabu.tree.element.impl.CClassDeclaration;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.PrimitiveTypeTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CArrayTypeTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CPrimitiveTypeTree;
import io.github.potjerodekool.nabu.tree.statement.impl.CVariableDeclaratorTree;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.util.Types;

import java.util.*;

public class EnumUserCodeGenerator implements CodeGenerator {

    private static final char ZERO = '0';
    private static final char NINE = '9';

    private final CompilerContextImpl compilerContext;
    private final Types types;

    private final Map<String, EnumUsageInfoImpl> enumUsageMap = new HashMap<>();

    public EnumUserCodeGenerator(final CompilerContextImpl compilerContext) {
        this.compilerContext = compilerContext;
        this.types = compilerContext.getClassElementLoader().getTypes();
    }

    @Override
    public void generateCode(final ClassDeclaration classDeclaration) {
    }

    public EnumUsage addEnumUsage(final CompilationUnit compilationUnit,
                                  final ClassDeclaration currentClass,
                                  final TypeElement typeElement) {
        final var className = currentClass.getClassSymbol().getQualifiedName();

        final var usageInfo = enumUsageMap.computeIfAbsent(className, key -> {
            final var memberClass = addMemberClass(compilationUnit, currentClass);
            return new EnumUsageInfoImpl(memberClass);
        });

        if (!usageInfo.hasEnumMapping(typeElement)) {
            final var memberClass = usageInfo.getMemberClass();
            final var field = createArrayField(typeElement, (ClassSymbol) memberClass.getClassSymbol());
            memberClass.enclosedElement(field);

            usageInfo.addEnumMapping(typeElement);
            usageInfo.addFieldMapping(typeElement, field.getName().getName());
        }

        return usageInfo;
    }

    private CVariableDeclaratorTree createArrayField(final TypeElement typeElement,
                                                     final ClassSymbol memberClazz) {
        final var name = new StringJoiner("$");
        name.add("$SwitchMap");

        for (final var subName : typeElement.getQualifiedName().split("\\.")) {
            name.add(subName);
        }

        final var type = new CArrayTypeTree(
                new CPrimitiveTypeTree(PrimitiveTypeTree.Kind.INT, -1, 1),
                List.of()
        );

        final var fieldTree = new CVariableDeclaratorTree(
                Kind.FIELD,
                new Modifiers(
                        Flags.STATIC + Flags.FINAL
                ),
                type,
                IdentifierTree.create(name.toString()),
                null,
                null
        );

        final var fieldSymbol = (VariableSymbol) new VariableSymbolBuilderImpl()
                .kind(ElementKind.FIELD)
                .simpleName(fieldTree.getName().getName())
                .type(types.getArrayType(types.getPrimitiveType(TypeKind.INT)))
                .flags(fieldTree.getFlags())
                .build();

        fieldTree.getName().setSymbol(fieldSymbol);
        memberClazz.addEnclosedElement(fieldSymbol);

        return fieldTree;
    }

    public CClassDeclaration addMemberClass(final CompilationUnit compilationUnit,
                                            final ClassDeclaration classDeclaration) {
        final var memberClassName = TreeFilter.classesIn(classDeclaration.getEnclosedElements()).stream()
                .map(ClassDeclaration::getSimpleName)
                .filter(this::isNumber)
                .map(Integer::valueOf)
                .map(it -> it + 1)
                .map(Object::toString)
                .findFirst()
                .orElse("1");

        final var classSymbol = (ClassSymbol) classDeclaration.getClassSymbol();
        var enclosingElement = classSymbol.getEnclosingElement();
        PackageSymbol packageSymbol;

        while (!(enclosingElement instanceof PackageElement)) {
            enclosingElement = enclosingElement.getEnclosingElement();
        }

        packageSymbol = (PackageSymbol) enclosingElement;

        final var memberClass = new ClassDeclarationBuilder()
                .kind(Kind.CLASS)
                .nestingKind(io.github.potjerodekool.nabu.tree.element.NestingKind.MEMBER)
                .simpleName(memberClassName)
                .extending(IdentifierTree.create(Constants.OBJECT))
                .modifiers(new Modifiers(Flags.STATIC + Flags.SYNTHETIC))
                .build();

        compilerContext.getSymbolGenerator().generate(
                compilationUnit,
                memberClass,
                packageSymbol
        );

        ((ClassSymbol) memberClass.getClassSymbol()).complete();

        final var symbolTable = SymbolTable.getInstance(compilerContext);

        final var memberClassSymbol = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .nestingKind(NestingKind.MEMBER)
                .simpleName(memberClassName)
                .flags(Flags.STATIC + Flags.SYNTHETIC)
                .superclass(symbolTable.getObjectType())
                .enclosingElement(classSymbol)
                .build();

        memberClassSymbol.setMembers(new WritableScope());

        memberClass.setClassSymbol(memberClassSymbol);
        classSymbol.getMembers().define(memberClassSymbol);
        classDeclaration.enclosedElement(memberClass);

        compilerContext.getEnumUsageMap().updateEnumUsage(
                classDeclaration,
                memberClassSymbol
        );

        return memberClass;
    }

    private boolean isNumber(final String value) {
        return value.chars()
                .allMatch(c -> isDigit((char) c));
    }

    private boolean isDigit(char c) {
        return c >= ZERO && c <= NINE;
    }

}

class EnumUsageInfoImpl implements EnumUsage {

    private final CClassDeclaration memberClass;
    private final Map<String, List<EnumMapping>> enumMappings = new HashMap<>();
    private final Map<String, String> fieldMappings = new HashMap<>();

    public EnumUsageInfoImpl(final CClassDeclaration memberClass) {
        this.memberClass = memberClass;
    }

    @Override
    public CClassDeclaration getMemberClass() {
        return memberClass;
    }

    public boolean hasEnumMapping(final TypeElement enumClass) {
        return enumMappings.containsKey(enumClass.getQualifiedName());
    }

    public void addEnumMapping(final TypeElement typeElement) {
        final var enumValues = ElementFilter.enumValuesIn(typeElement.getEnclosedElements());
        final var enumMappingList = new ArrayList<EnumMapping>();

        for (var i = 0; i < enumValues.size(); i++) {
            final var enumValue = enumValues.get(i);
            enumMappingList.add(new EnumMapping(enumValue.getSimpleName(), i + 1));
        }

        this.enumMappings.put(typeElement.getQualifiedName(), enumMappingList);
    }

    @Override
    public String getFieldName(final TypeElement enumClass) {
        return this.fieldMappings.get(enumClass.getQualifiedName());
    }

    public void addFieldMapping(final TypeElement enumClass,
                                final String fieldName) {
        this.fieldMappings.put(enumClass.getQualifiedName(), fieldName);
    }
}

record EnumMapping(String name,
                   int index) {

}