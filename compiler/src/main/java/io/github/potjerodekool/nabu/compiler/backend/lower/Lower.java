package io.github.potjerodekool.nabu.compiler.backend.lower;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.VariableSymbol;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.backend.lower.codegen.*;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.backend.lower.widen.WideningConverter;
import io.github.potjerodekool.nabu.compiler.resolve.Boxer;
import io.github.potjerodekool.nabu.compiler.resolve.method.MethodResolver;
import io.github.potjerodekool.nabu.compiler.resolve.TreeUtils;

import io.github.potjerodekool.nabu.compiler.resolve.asm.ClassSymbolLoader;
import io.github.potjerodekool.nabu.compiler.tree.*;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.Kind;
import io.github.potjerodekool.nabu.compiler.tree.element.ModuleDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.expression.impl.CArrayAccessExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.impl.CFieldAccessExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.builder.VariableDeclaratorTreeBuilder;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.ExecutableType;
import io.github.potjerodekool.nabu.compiler.type.PrimitiveType;
import io.github.potjerodekool.nabu.compiler.util.Types;

import java.util.*;

public class Lower extends AbstractTreeTranslator<LowerContext> {

    private final WideningConverter wideningConverter;
    private final Boxer boxer;
    private final Caster caster;
    private final MethodResolver methodResolver;
    private final Types types;
    private final ClassSymbolLoader loader;
    private int varCounter = 0;

    private final DefaultCodeGenerator defaultCodeGenerator;
    private final Map<Kind, AbstractCodeGenerator> codeGenerators = new HashMap<>();
    private final EnumUserCodeGenerator enumUserCodeGenerator;

    public Lower(final CompilerContextImpl compilerContext) {
        this.loader = compilerContext.getClassElementLoader();
        this.types = compilerContext.getClassElementLoader().getTypes();
        this.boxer = new Boxer(
                loader,
                compilerContext.getMethodResolver()
        );
        this.caster = new Caster();
        this.wideningConverter = new WideningConverter(types);
        this.methodResolver = compilerContext.getMethodResolver();

        this.defaultCodeGenerator = new DefaultCodeGenerator(loader);
        this.enumUserCodeGenerator = new EnumUserCodeGenerator(compilerContext);
        initCodeGenerators();
    }

    private void initCodeGenerators() {
        codeGenerators.put(Kind.ENUM, new EnumCodeGenerator(loader));
        codeGenerators.put(Kind.RECORD, new RecordCodeGenerator(loader));
    }

    public void process(final CompilationUnit compilationUnit) {
        final var context = new LowerContext();
        compilationUnit.accept(this, context);
    }

    private AbstractCodeGenerator getGenerator(final ClassDeclaration classDeclaration) {
        return this.codeGenerators.getOrDefault(classDeclaration.getKind(), defaultCodeGenerator);
    }

    @Override
    public Tree visitUnknown(final Tree tree,
                             final LowerContext context) {
        return tree;
    }

    @Override
    public Tree visitModuleDeclaration(final ModuleDeclaration moduleDeclaration, final LowerContext context) {
        context.module = moduleDeclaration.getModuleSymbol();
        return super.visitModuleDeclaration(moduleDeclaration, context);
    }

    @Override
    public Tree visitClass(final ClassDeclaration classDeclaration, final LowerContext context) {
        final var oldClass = context.currentClass;
        context.currentClass = classDeclaration;

        final var result = super.visitClass(classDeclaration, context);
        final var generator = getGenerator(classDeclaration);
        generator.generateCode(classDeclaration);

        context.currentClass = oldClass;
        return result;
    }

    @Override
    public Tree visitBinaryExpression(final BinaryExpressionTree binaryExpression,
                                      final LowerContext context) {
        var left = wideningConverter.convert(
                binaryExpression.getLeft(),
                binaryExpression.getRight()
        );

        var right = wideningConverter.convert(
                binaryExpression.getRight(),
                binaryExpression.getLeft()
        );

        left = TreeUtils.typeOf(right).accept(caster, left);
        right = TreeUtils.typeOf(left).accept(caster, right);

        left = unboxIfNeeded(left, right);
        right = unboxIfNeeded(right, left);

        if (left != binaryExpression.getLeft()
                || right != binaryExpression.getRight()) {
            return binaryExpression.builder()
                    .left(left)
                    .right(right)
                    .build();
        }

        return binaryExpression;
    }

    @Override
    public Tree visitEnhancedForStatement(final EnhancedForStatementTree enhancedForStatement,
                                          final LowerContext context) {
        final var expression = (ExpressionTree) enhancedForStatement.getExpression().accept(this, context);
        final var localVariable = (VariableDeclaratorTree) enhancedForStatement.getLocalVariable().accept(this, context);
        final var statement = (StatementTree) enhancedForStatement.getStatement().accept(this, context);

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

        final var resolvedMethod = methodResolver.resolveMethod(methodInvocation)
                .get();
        methodInvocation.getMethodSelector().setType(resolvedMethod.getOwner().asType());
        methodInvocation.setMethodType(resolvedMethod);

        final var localVariableType = (DeclaredType) localVariable.getType().getType();
        final var iteratorName = generateVariableName();
        final var iteratorClassElement = loader.loadClass(
                context.module,
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
                .type(iteratorTypeTree)
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

        final var resolvedHasNextMethod = methodResolver.resolveMethod(check)
                .get();
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

        final var resolvedNextMethod = methodResolver.resolveMethod(nextInvocation).get();
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
                .type(typeTree)
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
        final var leftType = TreeUtils.typeOf(left);
        final var rightType = TreeUtils.typeOf(right);

        if (leftType instanceof DeclaredType
                && rightType instanceof PrimitiveType primitiveType) {
            return primitiveType.accept(boxer, left);
        }

        return left;
    }

    @Override
    public Tree visitIdentifier(final IdentifierTree identifier, final LowerContext context) {
        //Insert this if needed.
        final var symbol = identifier.getSymbol();

        if (symbol instanceof VariableSymbol variableSymbol
                && isFieldOrEnumConstant(variableSymbol)
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

        return super.visitIdentifier(identifier, context);
    }

    private boolean isFieldOrEnumConstant(final VariableSymbol variableSymbol) {
        return variableSymbol.getKind() == ElementKind.FIELD
                || variableSymbol.getKind() == ElementKind.ENUM_CONSTANT;
    }

    @Override
    public Tree visitSwitchStatement(final SwitchStatement switchStatement,
                                     final LowerContext context) {
        var selector = (ExpressionTree) switchStatement.getSelector().accept(this, context);

        final var selectorType = selector.getSymbol().asType();

        if (types.isBoxType(selectorType)) {
            final var primitiveType = types.unboxedType(selectorType);
            selector = primitiveType.accept(boxer, selector);
        } else if (selectorType.isReferenceType()
                && selectorType.asElement() instanceof TypeElement enumElement
                && enumElement.getKind() == ElementKind.ENUM) {

            return visitEnumSwitchStatement(
                    switchStatement,
                    context,
                    enumElement);
        }

        final var cases = switchStatement.getCases().stream()
                .map(caseStatement -> caseStatement.accept(this, context))
                .map(it -> (CaseStatement) it)
                .toList();

        return switchStatement.builder()
                .selector(selector)
                .cases(cases)
                .build();
    }

    private SwitchStatement visitEnumSwitchStatement(final SwitchStatement switchStatement,
                                                     final LowerContext context,
                                                     final TypeElement enumElement) {
        var selector = (ExpressionTree) switchStatement.getSelector().accept(this, context);

        final var currentClass = context.currentClass;

        final var enumUsage = enumUserCodeGenerator.addEnumUsage(currentClass, enumElement);
        final var memberClass = enumUsage.getMemberClass().getClassSymbol();
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
                .map(caseStatement -> caseStatement.accept(this, context))
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
                .get();

        return (ExecutableType) ordinalMethod.asType();
    }
}

class LowerContext {

    ModuleElement module;
    ClassDeclaration currentClass;

}