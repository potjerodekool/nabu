package io.github.potjerodekool.nabu.compiler.internal;

import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;

import java.util.*;

public class EnumUsageMap {

    //classname = {  }
    private final Map<String, LinkedHashMap<ClassKey, UsageInfo>> enumUsageMap = new HashMap<>();

    private final Map<String, ClassUsage> usageMap = new HashMap<>();


    public void addEnumUsage(final ClassDeclaration useClass,
                             final TypeElement enumClass) {
        final var useClassName = useClass.getClassSymbol().getQualifiedName();
        final var usage = usageMap.computeIfAbsent(useClassName, key -> new ClassUsage());
    }

    public void registerClass(final ClassDeclaration useClass) {
        final var useClassName = useClass.getClassSymbol().getQualifiedName();
        enumUsageMap.computeIfAbsent(useClassName, key -> new LinkedHashMap<>());
    }

    public void registerEnumUsage(final TypeElement useClass,
                                  final VariableElement enumConstant) {
        final var useClassName = useClass.getQualifiedName();
        final var enumClass = (ClassSymbol) enumConstant.getEnclosingElement();
        final var enumMap = enumUsageMap.computeIfAbsent(useClassName, key -> new LinkedHashMap<>());
        final var usageInfo = enumMap.computeIfAbsent(new ClassKey(enumClass), key -> new UsageInfo());
        final var usedConstantsMap = usageInfo.mapping;
        usedConstantsMap.putIfAbsent(enumConstant.getSimpleName(), usedConstantsMap.size() + 1);
    }

    public HashMap<String, Map<String, Integer>> getEnumUsage(final ClassDeclaration classDeclaration) {
        final var result = new HashMap<String, Map<String, Integer>>();

        final var useClassName = classDeclaration.getClassSymbol().getQualifiedName();
        final var map = enumUsageMap.get(useClassName);

        if (map == null) {
            return result;
        }

        map.forEach((key, usageInfo) -> {
            final var enumClassName = key.classSymbol().getQualifiedName();
            final var constantMappingMap = usageInfo.mapping;
            result.put(enumClassName, constantMappingMap);
        });
        return result;
    }

    public Map<String, Integer> getEnumSwitchMap(final ClassDeclaration classDeclaration,
                                                 final ClassSymbol enumClass) {
        final var useClassName = classDeclaration.getClassSymbol().getQualifiedName();
        final var map = enumUsageMap.get(useClassName);

        if (map == null) {
            return null;
        }

        final var usageInfo = map.get(new ClassKey(enumClass));

        if (usageInfo == null) {
            return Collections.emptyMap();
        }

        return usageInfo.mapping;
    }

    public void updateEnumUsage(final ClassDeclaration useClass,
                                final ClassSymbol memberClassSymbol) {
        final var usageClassSymbol = (ClassSymbol) useClass.getClassSymbol();

        final var useClassName = usageClassSymbol.getQualifiedName();
        final var enumMap = enumUsageMap.computeIfAbsent(useClassName, key -> new LinkedHashMap<>());

        enumMap.values().forEach(usageInfo -> {
            usageInfo.memberClass = memberClassSymbol;
        });
    }

    private UsageInfo getUsageInfo() {
        return null;
    }
}

record ClassKey(ClassSymbol classSymbol) {

    @Override
    public boolean equals(final Object obj) {
        return obj instanceof ClassKey(ClassSymbol symbol)
                && classSymbol.getQualifiedName().equals(symbol.getQualifiedName());
    }

    @Override
    public int hashCode() {
        return classSymbol.getQualifiedName().hashCode();
    }
}

class ClassUsage {

    ClassSymbol memberClass;
}

class UsageInfo {

    Map<String, Integer> mapping = new HashMap<>();

    ClassSymbol memberClass;

}