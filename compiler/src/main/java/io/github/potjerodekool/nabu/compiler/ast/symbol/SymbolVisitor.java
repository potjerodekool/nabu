package io.github.potjerodekool.nabu.compiler.ast.symbol;

public interface SymbolVisitor<R,P> {

    R visitUnknown(Symbol symbol, P p);

    default R visit(Symbol symbol,
                    P p) {
        return symbol.accept(this, p);
    }

    default R visit(Symbol symbol) {
        return visit(symbol, null);
    }

    default R visitClass(ClassSymbol classSymbol, P p) {
        return visitUnknown(classSymbol, p);
    }

    default R visitMethod(MethodSymbol methodSymbol, P p) {
        return visitUnknown(methodSymbol, p);
    }

    default R visitPackage(PackageSymbol packageSymbol, P p) {
        return visitUnknown(packageSymbol, p);
    }

    default R visitTypeVariable(TypeVariableSymbol typeVariableSymbol, P p) {
        return visitUnknown(typeVariableSymbol, p);
    }

    default R visitVariable(VariableSymbol variableSymbol, P p) {
        return visitUnknown(variableSymbol, p);
    }

    default R visitModule(ModuleSymbol moduleSymbol, P p) {
        return visitUnknown(moduleSymbol, p);
    }
}
