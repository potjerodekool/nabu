package io.github.potjerodekool.nabu.compiler.backend.lower;

import io.github.potjerodekool.nabu.compiler.ast.symbol.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.VariableSymbol;
import io.github.potjerodekool.nabu.compiler.backend.lower.codegen.*;
import io.github.potjerodekool.nabu.compiler.backend.lower.widen.WideningConverter;
import io.github.potjerodekool.nabu.compiler.impl.CompilerContextImpl;
import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.compiler.resolve.impl.Boxer;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.resolve.method.MethodResolver;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.element.ModuleDeclaration;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.tree.expression.impl.CArrayAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CFieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.*;
import io.github.potjerodekool.nabu.tree.statement.builder.VariableDeclaratorTreeBuilder;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.ExecutableType;
import io.github.potjerodekool.nabu.type.PrimitiveType;
import io.github.potjerodekool.nabu.util.Types;

import java.util.*;


/**
 * Add code like:
 * Generate code to init fields (both in constructor and client init).
 * Generate code for enum and record classes.
 * Add boxing and unboxing code.
 * Add code to enhanced for statements.
 */
public class Lower extends AbstractTreeTranslator<Lower.LowerScope> {

    private final CompilerContextImpl compilerContext;
    private final WideningConverter wideningConverter;
    private final Boxer boxer;
    private final Caster caster;
    private final MethodResolver methodResolver;
    private final Types types;
    private final ClassElementLoader loader;
    private int varCounter = 0;

    private final DefaultCodeGenerator defaultCodeGenerator;
    private final Map<Kind, AbstractCodeGenerator> codeGenerators = new HashMap<>();
    private final EnumUserCodeGenerator enumUserCodeGenerator;

    public Lower(final CompilerContextImpl compilerContext) {
        this.compilerContext = compilerContext;
        this.loader = compilerContext.getClassElementLoader();
        this.types = compilerContext.getTypes();
        this.boxer = new Boxer(compilerContext);
        this.caster = new Caster();
        this.wideningConverter = new WideningConverter(compilerContext);
        this.methodResolver = compilerContext.getMethodResolver();

        this.defaultCodeGenerator = new DefaultCodeGenerator(compilerContext);
        this.enumUserCodeGenerator = new EnumUserCodeGenerator(compilerContext);
        initCodeGenerators();
    }

    public static CompilationUnit lower(final CompilationUnit compilationUnit,
                                        final CompilerContext compilerContext) {
        final var lower = new Lower((CompilerContextImpl) compilerContext);
        lower.process(compilationUnit);
        return compilationUnit;
    }

    private void initCodeGenerators() {
        codeGenerators.put(Kind.ENUM, new EnumCodeGenerator(compilerContext));
        codeGenerators.put(Kind.RECORD, new RecordCodeGenerator(compilerContext));
    }

    public void process(final CompilationUnit compilationUnit) {
        final var scope = new LowerScope(compilationUnit);
        acceptTree(compilationUnit, scope);
    }

    private AbstractCodeGenerator getGenerator(final ClassDeclaration classDeclaration) {
        return this.codeGenerators.getOrDefault(classDeclaration.getKind(), defaultCodeGenerator);
    }

    @Override
    public Tree visitUnknown(final Tree tree,
                             final LowerScope context) {
        return tree;
    }

    public Tree defaultAnswer(final Tree tree,
                              final LowerScope scope) {
        return tree;
    }

    @Override
    public Tree visitModuleDeclaration(final ModuleDeclaration moduleDeclaration,
                                       final LowerScope scope) {
        scope.setModuleElement(moduleDeclaration.getModuleSymbol());
        return super.visitModuleDeclaration(moduleDeclaration, scope);
    }

    @Override
    public Tree visitClass(final ClassDeclaration classDeclaration, final LowerScope scope) {
        final var oldClass = scope.getCurrentClassDeclaration();
        scope.setCurrentClassDeclaration(classDeclaration);

        final var result = super.visitClass(classDeclaration, scope);
        final var generator = getGenerator(classDeclaration);
        generator.generateCode(classDeclaration);
        scope.setCurrentClassDeclaration(oldClass);
        return result;
    }

    @Override
    public Tree visitBinaryExpression(final BinaryExpressionTree binaryExpression,
                                      final LowerScope scope) {
        var left = binaryExpression.getLeft();
        var right = binaryExpression.getRight();

        if (scope != null) {
            final var leftType = compilerContext.getTreeUtils().typeOf(left);
            final var rightType = compilerContext.getTreeUtils().typeOf(right);
            final var module = scope.findModuleElement();
            final var stringType = loader.loadClass(module, Constants.STRING).asType();

            if ((leftType != null && types.isSameType(stringType, leftType))
                    || (rightType != null && types.isSameType(stringType, rightType))) {
                return binaryExpression;
            }
        }

        left = wideningConverter.convert(left, right);
        right = wideningConverter.convert(right, left);

        left = compilerContext.getTreeUtils().typeOf(right).accept(caster, left);
        right = compilerContext.getTreeUtils().typeOf(left).accept(caster, right);

        left = unboxIfNeeded(left, right);
        right = unboxIfNeeded(right, left);

        if (left != binaryExpression.getLeft()
                || right != binaryExpression.getRight()) {
            return binaryExpression.builder()
                    .left(left)
                    .right(right)
                    .type(left.getType())
                    .build();
        }

        return binaryExpression;
    }

    @Override
    public Tree visitEnhancedForStatement(final EnhancedForStatementTree enhancedForStatement,
                                          final LowerScope scope) {
        final var expression = (ExpressionTree) acceptTree(enhancedForStatement.getExpression(), scope);
        final var localVariable = (VariableDeclaratorTree) acceptTree(enhancedForStatement.getLocalVariable(), scope);
        final var statement = (StatementTree) acceptTree(enhancedForStatement.getStatement(), scope);

        var methodInvocation = TreeMaker.methodInvocationTree(
                new CFieldAccessExpressionTree(
                        expression,
                        IdentifierTree.create("iterator")
                ),
                List.of(),
                List.of(),
                -1,
                -1
        );

        methodResolver.resolveMethod(methodInvocation, scope)
                .ifPresent(resolvedMethod -> {
                    methodInvocation.getMethodSelector().setType(resolvedMethod.getOwner().asType());
                    methodInvocation.setMethodType(resolvedMethod);
                });

        /*
        methodResolver.resolveMethod(methodInvocation).ifPresent(resolvedMethod -> {
            methodInvocation.getMethodSelector().setType(resolvedMethod.getOwner().asType());
            methodInvocation.setMethodType(resolvedMethod);
        });
        */

        final var localVariableType = (DeclaredType) localVariable.getVariableType().getType();
        final var iteratorName = generateVariableName();
        final var iteratorClassElement = loader.loadClass(
                scope.findModuleElement(),
                "java.util.Iterator"
        );

        final var iteratorType = types.getDeclaredType(iteratorClassElement, localVariableType);

        final var localVariableElement = new VariableSymbolBuilderImpl()
                .kind(ElementKind.LOCAL_VARIABLE)
                .simpleName(iteratorName)
                .type(iteratorType)
                .build();

        final var iteratorTypeTree = TreeMaker.typeApplyTree(
                IdentifierTree.create("java.util.Iterator"),
                List.of(),
                -1,
                -1
        );

        iteratorTypeTree.setType(iteratorType);

        final var forInit = new VariableDeclaratorTreeBuilder()
                .kind(Kind.LOCAL_VARIABLE)
                .modifiers(new Modifiers())
                .variableType(iteratorTypeTree)
                .name(createIdentifier(iteratorName, localVariableElement))
                .value(methodInvocation)
                .build();

        final var check = TreeMaker.methodInvocationTree(
                new CFieldAccessExpressionTree(
                        createIdentifier(iteratorName, localVariableElement),
                        IdentifierTree.create("hasNext")
                ),
                List.of(),
                List.of(),
                -1,
                -1
        );

        final var resolvedHasNextMethod = methodResolver.resolveMethod(check, scope)
                .orElseThrow(() -> new IllegalStateException("Failed to resolve hasNext method"));

        check.getMethodSelector().setType(resolvedHasNextMethod.getOwner().asType());
        check.setMethodType(resolvedHasNextMethod);

        final var typeTree = createIdentifier(localVariableType);

        final var nextInvocation = TreeMaker.methodInvocationTree(
                new CFieldAccessExpressionTree(
                        createIdentifier(iteratorName, localVariableElement),
                        IdentifierTree.create("next")
                ),
                List.of(),
                List.of(),
                -1,
                -1
        );

        final var resolvedNextMethod = methodResolver.resolveMethod(nextInvocation, scope)
                .orElseThrow(() -> new IllegalStateException("Failed to resolve next method"));

        nextInvocation.getMethodSelector().setType(resolvedNextMethod.getOwner().asType());
        nextInvocation.setMethodType(resolvedNextMethod);

        final var castTypeTree = IdentifierTree.create(typeTree.getName());

        castTypeTree.setType(typeTree.getType());

        final var cast = TreeMaker.castExpressionTree(
                castTypeTree,
                nextInvocation,
                -1,
                -1
        );

        cast.setType(localVariableType);

        final var statements = new ArrayList<StatementTree>();
        statements.add(localVariable.builder()
                .variableType(typeTree)
                .value(cast)
                .build());

        if (statement instanceof BlockStatementTree blockStatement) {
            statements.addAll(blockStatement.getStatements());
        } else {
            statements.add(statement);
        }

        final var newBody = TreeMaker.blockStatement(
                statements,
                -1,
                -1
        );

        return TreeMaker.forStatement(
                List.of(forInit),
                check,
                List.of(),
                newBody,
                -1,
                -1
        );
    }

    private IdentifierTree createIdentifier(final DeclaredType declaredType) {
        final var classSymbol = (TypeElement) declaredType.asElement();
        final var className = classSymbol.getQualifiedName();
        final var identifier = IdentifierTree.create(className);
        identifier.setType(declaredType);
        return identifier;
    }

    private IdentifierTree createIdentifier(final String name,
                                            final Element element) {
        final var identifier = IdentifierTree.create(name);
        identifier.setSymbol(element);
        return identifier;
    }

    private String generateVariableName() {
        return "$p" + varCounter++;
    }

    public ExpressionTree unboxIfNeeded(final ExpressionTree left,
                                        final ExpressionTree right) {
        final var leftType = compilerContext.getTreeUtils().typeOf(left);
        final var rightType = compilerContext.getTreeUtils().typeOf(right);

        if (leftType instanceof DeclaredType
                && rightType instanceof PrimitiveType primitiveType) {
            return primitiveType.accept(boxer, left);
        }

        return left;
    }

    @Override
    public Tree visitFieldAccessExpression(final FieldAccessExpressionTree fieldAccessExpression,
                                           final LowerScope scope) {
        final var selected = access(fieldAccessExpression.getSelected(), null);
        final var field = fieldAccessExpression.getField();
        return fieldAccessExpression.builder()
                .selected(selected)
                .field(field)
                .build();
    }

    @Override
    public Tree visitIdentifier(final IdentifierTree identifier,
                                final LowerScope scope) {
        final var symbol = identifier.getSymbol();

        if (symbol instanceof VariableSymbol variableSymbol
                && variableSymbol.getKind() == ElementKind.FIELD
                && !variableSymbol.isStatic()) {

            final var clazz = symbol.getEnclosingElement();
            final var thisIdentifier = TreeMaker.identifier(Constants.THIS, -1, -1);
            thisIdentifier.setSymbol(clazz);

            return TreeMaker.fieldAccessExpressionTree(
                    thisIdentifier,
                    identifier,
                    -1,
                    -1
            );
        }

        return super.visitIdentifier(identifier, scope);
    }

    private ExpressionTree access(final ExpressionTree field,
                                  final ExpressionTree selected) {
        final var symbol = field.getSymbol();

        if (selected instanceof MethodInvocationTree) {
            return field;
        }

        if (!isThisExpression(selected)
                && symbol instanceof VariableSymbol variableSymbol
                && isFieldOrEnumConstant(variableSymbol)
                && !variableSymbol.isStatic()) {
            final var clazz = symbol.getEnclosingElement();
            final var thisIdentifier = TreeMaker.identifier(Constants.THIS, -1, -1);
            thisIdentifier.setSymbol(clazz);

            return TreeMaker.fieldAccessExpressionTree(
                    thisIdentifier,
                    (IdentifierTree) field,
                    -1,
                    -1
            );
        }

        return field;
    }

    private boolean isThisExpression(final ExpressionTree expressionTree) {
        return expressionTree instanceof IdentifierTree identifierTree
                && Constants.THIS.equals(identifierTree.getName());
    }

    private boolean isFieldOrEnumConstant(final VariableSymbol variableSymbol) {
        return variableSymbol.getKind() == ElementKind.FIELD
                || variableSymbol.getKind() == ElementKind.ENUM_CONSTANT;
    }

    @Override
    public Tree visitSwitchStatement(final SwitchStatement switchStatement,
                                     final LowerScope scope) {
        var selector = (ExpressionTree) acceptTree(switchStatement.getSelector(), scope);

        final var selectorType = selector.getSymbol().asType();

        if (types.isBoxType(selectorType)) {
            final var primitiveType = types.unboxedType(selectorType);
            selector = primitiveType.accept(boxer, selector);
        } else if (selectorType.isReferenceType()
                && selectorType.asElement() instanceof TypeElement enumElement
                && enumElement.getKind() == ElementKind.ENUM) {

            return visitEnumSwitchStatement(
                    switchStatement,
                    scope,
                    enumElement);
        }

        final var cases = switchStatement.getCases().stream()
                .map(caseStatement -> acceptTree(caseStatement, scope))
                .map(it -> (CaseStatement) it)
                .toList();

        return switchStatement.builder()
                .selector(selector)
                .cases(cases)
                .build();
    }

    private SwitchStatement visitEnumSwitchStatement(final SwitchStatement switchStatement,
                                                     final LowerScope scope,
                                                     final TypeElement enumElement) {
        var selector = (ExpressionTree) acceptTree(switchStatement.getSelector(), scope);

        final var currentClass = scope.getCurrentClassDeclaration();

        final var enumUsage = enumUserCodeGenerator.addEnumUsage(
                scope.getCompilationUnit(),
                currentClass,
                enumElement
        );

        final var memberClass = (ClassSymbol) enumUsage.getMemberClass().getClassSymbol();
        final var fieldName = enumUsage.getFieldName(enumElement);

        final var selectorIdentifier = IdentifierTree.create(memberClass.getFlatName());
        selectorIdentifier.setType(memberClass.asType());

        final var fieldSymbol = memberClass.getMembers().resolveElement(fieldName);

        final var field = IdentifierTree.create(fieldName);
        field.setSymbol(fieldSymbol);

        final var fieldAccess = new CFieldAccessExpressionTree(
                selectorIdentifier,
                field
        );

        final var ordinal = IdentifierTree.create("ordinal");

        final var methodInvoke = TreeMaker.methodInvocationTree(
                new CFieldAccessExpressionTree(
                        selector,
                        ordinal
                ),
                List.of(),
                List.of(),
                -1,
                -1
        );

        methodInvoke.setMethodType(ordinalMethod(enumElement));

        final var newSelector = new CArrayAccessExpressionTree(fieldAccess, methodInvoke);

        final var cases = switchStatement.getCases().stream()
                .map(caseStatement ->
                        acceptTree(caseStatement, scope))
                .map(it -> (CaseStatement) it)
                .toList();

        return switchStatement.builder()
                .selector(newSelector)
                .cases(cases)
                .build();
    }

    private ExecutableType ordinalMethod(final TypeElement usedEnumClass) {
        final var enumClass = usedEnumClass.getSuperclass().asTypeElement();
        final var ordinalMethod = ElementFilter.methodsIn(enumClass.getEnclosedElements()).stream()
                .filter(it -> "ordinal".equals(it.getSimpleName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to resolve ordinal method"));

        return (ExecutableType) ordinalMethod.asType();
    }

    @Override
    public Tree visitFunction(final Function function,
                              final LowerScope scope) {
        return super.visitFunction(function, scope);
    }

    @Override
    public Tree visitVariableDeclaratorStatement(final VariableDeclaratorTree variableDeclaratorStatement,
                                                 final LowerScope scope) {
        var newValue = (ExpressionTree) accept(variableDeclaratorStatement.getValue(), scope);
        //Add cast if needed.
        newValue = variableDeclaratorStatement
                .getVariableType()
                .getType()
                .accept(caster, newValue);
        return variableDeclaratorStatement.builder()
                .value(newValue)
                .build();
    }

    @Override
    public Tree visitMethodInvocation(final MethodInvocationTree methodInvocation,
                                      final LowerScope scope) {
        final String methodName;

        if (methodInvocation.getMethodSelector() instanceof IdentifierTree
                identifierTree) {
            methodName = identifierTree.getName();
        } else if (methodInvocation.getMethodSelector() instanceof FieldAccessExpressionTree fieldAccessExpressionTree) {
            methodName = fieldAccessExpressionTree.getField().getName();
        } else {
            methodName = "";
        }

        final var methodType = methodInvocation.getMethodType();
        final var arguments = methodInvocation.getArguments();
        final var paramTypes = methodType.getParameterTypes();
        final var newArguments = new ArrayList<ExpressionTree>();
        var argChanged = false;

        for (var i = 0; i < paramTypes.size(); i++) {
            final var paramType = paramTypes.get(i);
            final var argument = arguments.get(i);
            final var newArgument = paramType.accept(boxer, argument);

            if (newArgument != argument) {
                argChanged = true;
            }
            newArguments.add(newArgument);
        }

        if (argChanged) {
            return methodInvocation.builder()
                    .arguments(newArguments)
                    .build();
        } else {
            return methodInvocation;
        }
    }

    public static class LowerScope implements Scope {

        private final CompilationUnit compilationUnit;
        private ModuleElement moduleElement;
        private ClassDeclaration currentClassDeclaration;
        private TypeElement currentClass;

        LowerScope(final CompilationUnit compilationUnit) {
            this.compilationUnit = compilationUnit;
        }

        @Override
        public CompilationUnit getCompilationUnit() {
            return compilationUnit;
        }

        @Override
        public ModuleElement findModuleElement() {
            return moduleElement;
        }

        public void setModuleElement(final ModuleElement moduleElement) {
            this.moduleElement = moduleElement;
        }

        public ClassDeclaration getCurrentClassDeclaration() {
            return currentClassDeclaration;
        }

        public void setCurrentClassDeclaration(final ClassDeclaration currentClassDeclaration) {
            this.currentClassDeclaration = currentClassDeclaration;
        }

        @Override
        public TypeElement getCurrentClass() {
            if (currentClass == null && currentClassDeclaration != null) {
                return currentClassDeclaration.getClassSymbol();
            }

            return currentClass;
        }

        public void setCurrentClass(final TypeElement currentClass) {
            this.currentClass = currentClass;
        }

        @Override
        public void define(final Element element) {
        }
    }

}

