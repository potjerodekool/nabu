package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.backend.CompileException;
import io.github.potjerodekool.nabu.compiler.backend.asm.JvmSignatureBuilder;
import io.github.potjerodekool.nabu.compiler.ir.*;
import io.github.potjerodekool.nabu.compiler.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.compiler.ir.instructions.IRInstruction.BinaryOp.Op;
import io.github.potjerodekool.nabu.compiler.ir.types.IRType;
import io.github.potjerodekool.nabu.compiler.ir.values.IRValue;
import io.github.potjerodekool.nabu.compiler.lang.Flags;
import io.github.potjerodekool.nabu.compiler.lang.model.element.Element;
import io.github.potjerodekool.nabu.compiler.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.compiler.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.lang.model.element.TypeParameterElement;
import io.github.potjerodekool.nabu.compiler.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.lang.model.element.CompoundAttribute;
import io.github.potjerodekool.nabu.tree.*;
import io.github.potjerodekool.nabu.tree.element.*;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.element.NestingKind;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.tree.expression.impl.CIdentifierTree;
import io.github.potjerodekool.nabu.tree.statement.*;
import io.github.potjerodekool.nabu.type.ExecutableType;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.type.TypeVariable;

import java.util.*;

/**
 * Vertaalt de Nabu AST naar een IRModule.
 * <p>
 * Implementeert TreeVisitor<IRValue, IRBuilder>:
 * - R = IRValue — elke visit-methode geeft de IR-waarde terug
 * die de expressie vertegenwoordigt (null voor statements)
 * - P = IRBuilder — de actieve builder wordt als parameter doorgegeven
 * <p>
 * Gebruik:
 * var visitor = new IrGeneratingVisitor();
 * compilationUnit.accept(visitor, null);
 * IRModule module = visitor.getModule();
 */
public class IrGeneratingVisitor extends AbstractTreeVisitor<IRValue, IRBuilder> {

    private IRBuilder builder;
    private IRModule module;
    private final List<IRModule> modules = new ArrayList<>();

    private final ScopeTracker scope = new ScopeTracker();

    // Naam van de klasse die nu verwerkt wordt (voor this-verwijzingen)
    private String currentClassName;

    // Teller voor unieke lambda-functienamen
    private int lambdaCounter = 0;

    // Loop-context stack voor break/continue
    private final Deque<IRBasicBlock> breakTargets = new ArrayDeque<>();
    private final Deque<IRBasicBlock> continueTargets = new ArrayDeque<>();

    // Gelabelde break/continue doelen
    private final Map<String, IRBasicBlock> labeledBreakTargets = new LinkedHashMap<>();
    private final Map<String, IRBasicBlock> labeledContinueTargets = new LinkedHashMap<>();

    // Labels die wachten op een continue target (gevuld door de volgende lus)
    private final Deque<String> pendingLabels = new ArrayDeque<>();

    // Try-catch ranges die verzameld worden voor de ASM-backends
    private final List<TryCatchRange> tryCatchRanges = new ArrayList<>();

    public record TryCatchRange(String tryStart, String tryEnd, String handler, String exceptionType) {}

    // -------------------------------------------------------
    // Resultaat
    // -------------------------------------------------------

    public IRModule getModule() {
        return modules.isEmpty() ? null : modules.get(0);
    }

    public List<IRModule> getModules() {
        return Collections.unmodifiableList(modules);
    }

    public List<TryCatchRange> getTryCatchRanges() {
        return Collections.unmodifiableList(tryCatchRanges);
    }

    // -------------------------------------------------------
    // Fallback
    // -------------------------------------------------------

    @Override
    public IRValue visitUnknown(Tree tree, IRBuilder param) {
        return null;
    }

    // -------------------------------------------------------
    // CompilationUnit
    // -------------------------------------------------------

    @Override
    public IRValue visitCompilationUnit(CompilationUnit compilationUnit,
                                        IRBuilder param) {
        String fileName = compilationUnit.getFileObject() != null
                ? compilationUnit.getFileObject().getFileName()
                : "<onbekend>";

        compilationUnit.getClasses().stream()
                .filter(it -> it.getNestingKind() == NestingKind.TOP_LEVEL)
                .findFirst()
                .orElseThrow(() -> new RuntimeException(new CompileException("No toplevel class")));

        // Bronbestand registreren voor debuginfo
        int lastSlash = fileName.lastIndexOf('/');
        String dir = lastSlash >= 0 ? fileName.substring(0, lastSlash) : ".";
        String file = lastSlash >= 0 ? fileName.substring(lastSlash + 1) : fileName;

        // Elke top-level class krijgt een eigen IRModule.
        // Voorheen werd de hele CU in één module geëmit waardoor methodes en
        // constructors van meerdere classes in de eerste class terechtkwamen.
        for (ClassDeclaration cls : compilationUnit.getClasses()) {
            resetClassState();

            final var classSymbol = cls.getClassSymbol();
            final var moduleName = classSymbol.getQualifiedName();
            long classFlags = Flags.parse(classSymbol.getModifiers());
            if (classSymbol.getKind().isInterface()) {
                classFlags |= Flags.INTERFACE;
            }
            builder = new IRBuilder(classFlags, moduleName);
            module = builder.build();
            module.setSourceFile(file, dir);

            acceptTree(cls, builder);

            modules.add(module);
        }

        return null;
    }

    private void resetClassState() {
        currentClassName = null;
        lambdaCounter = 0;
        scope.reset();
        breakTargets.clear();
        continueTargets.clear();
        labeledBreakTargets.clear();
        labeledContinueTargets.clear();
        pendingLabels.clear();
        tryCatchRanges.clear();
    }

    // -------------------------------------------------------
    // Klasse-declaratie
    // -------------------------------------------------------

    @Override
    public IRValue visitClass(ClassDeclaration classDeclaration,
                              IRBuilder param) {
        currentClassName = classDeclaration.getSimpleName();

        final var classSymbol = classDeclaration.getClassSymbol();
        if (classSymbol != null) {
            final var annotationMirrors = classSymbol.getAnnotationMirrors();
            module.setAnnotations(annotationMirrors.stream()
                    .filter(a -> a instanceof CompoundAttribute)
                    .map(a -> (CompoundAttribute) a)
                    .toList());

            // Set super type and interfaces with full generic info
            final var superMirror = classSymbol.getSuperclass();
            if (superMirror != null
                    && superMirror.getKind() != TypeKind.NONE
                    && !isJavaLangObject(superMirror)) {
                builder.superType(TypeMirrorToIRType.map(superMirror));
            }

            final var ifaces = classSymbol.getInterfaces();
            if (ifaces != null && !ifaces.isEmpty()) {
                builder.interfaces(ifaces.stream()
                        .map(TypeMirrorToIRType::map)
                        .toList());
            }

            // Generate class-level generic signature
            final var classSignature = JvmSignatureBuilder.buildClassSignature(classSymbol);
            if (classSignature != null) {
                module.setGenericSignature(classSignature);
            }
        }

        if (classDeclaration.getKind() == Kind.RECORD) {
            final var compactConstructorOptional = TreeFilter.constructorsIn(classDeclaration.getEnclosedElements()).stream()
                    .filter(c -> c.hasFlag(Flags.COMPACT_RECORD_CONSTRUCTOR))
                    .findFirst();

            compactConstructorOptional.ifPresent(compactConstructor -> {
                compactConstructor.getParameters().forEach(recordComponent -> {

                    module.emitField(
                            IRField.recordComponent(
                                    recordComponent.getName().getName(),
                                    TypeMirrorToIRType.map(recordComponent.getType())
                            )
                    );

                    acceptTree(recordComponent, param);
                });
            });
        }

        // Traverseer methoden en constructors
        for (Tree member : classDeclaration.getEnclosedElements()) {
            if (member instanceof Function function) {
                acceptTree(function, builder);
            } else if (member instanceof VariableDeclaratorTree variableDeclaratorTree) {
                acceptTree(variableDeclaratorTree, builder);
            }
        }
        return null;
    }

    // -------------------------------------------------------
    // Methode / constructor declaratie
    // -------------------------------------------------------

    @Override
    public IRValue visitFunction(Function function, IRBuilder param) {
        final var methodSymbol = (MethodSymbol) function.getMethodSymbol();
        if (methodSymbol == null) return null;

        // Locatie registreren
        builder.setLocation(
                currentClassName + ".nabu",
                function.getLineNumber(),
                function.getColumnNumber()
        );

        // Returntype
        IRType returnType = TypeMirrorToIRType.mapReturnType(
                methodSymbol.getReturnType());

        // Parameters
        List<IRValue> params = new ArrayList<>();
        for (VariableElement param2 : methodSymbol.getParameters()) {
            IRType paramType = TypeMirrorToIRType.map(param2.asType());
            params.add(new IRValue.Temp(
                    "%" + param2.getSimpleName(), paramType));
        }

        // Voor instantie-methoden: 'this' als eerste parameter
        boolean isStatic = methodSymbol.isStatic();
        if (!isStatic) {
            final var thisTypeMirror = methodSymbol.getEnclosingElement().asType();
            final var thisDescriptor = TypeMirrorToIRType.toJvmDescriptor(thisTypeMirror);
            params.addFirst(new IRValue.Temp("%this", new IRType.Ptr(IRType.I8, thisDescriptor)));
        }

        // Functienaam: klasse + methode (JVM-stijl intern)
        String fnName = currentClassName + "_" + function.getSimpleName();
        if (methodSymbol.getKind() == ElementKind.CONSTRUCTOR) {
            fnName = currentClassName + "_init";
        }

        final var flags = methodSymbol.getFlags();

        // Begin functie
        scope.reset();
        final var irFunction = builder.beginFunction(fnName, returnType, params, flags, methodSymbol.getKind() ==  ElementKind.CONSTRUCTOR);

        // Annotations overnemen van symbol naar IRFunction
        final var annotationMirrors = methodSymbol.getAnnotationMirrors();
        irFunction.setAnnotations(annotationMirrors.stream()
                .filter(a -> a instanceof CompoundAttribute)
                .map(a -> (CompoundAttribute) a)
                .toList());

        // Parameter-annotations overnemen
        final var paramAnnotations = new ArrayList<List<CompoundAttribute>>();
        if (!isStatic) {
            paramAnnotations.add(List.of()); // 'this' heeft geen annotations
        }
        for (VariableElement p : methodSymbol.getParameters()) {
            final var pa = p.getAnnotationMirrors().stream()
                    .filter(a -> a instanceof CompoundAttribute)
                    .map(a -> (CompoundAttribute) a)
                    .toList();
            paramAnnotations.add(pa);
        }
        irFunction.setParameterAnnotations(paramAnnotations);

        // Method-level generic signature
        final var typeParamElements = methodSymbol.getTypeParameters();
        final var paramTypeMirrors = methodSymbol.getParameters().stream()
                .map(VariableElement::asType)
                .toList();
        final var methodSig = JvmSignatureBuilder.buildMethodSignature(
                typeParamElements.toArray(new TypeParameterElement[0]),
                methodSymbol.getReturnType(),
                paramTypeMirrors
        );
        if (methodSig != null) {
            irFunction.setGenericSignature(methodSig);
        }

        // Parameters registreren in scope
        scope.pushScope();
        for (IRValue p : params) {
            final var name = IRValue.nameOf(p);
            String pname = name.startsWith("%")
                    ? name.substring(1) : name;
            scope.define(pname, p);
        }

        // Body
        if (function.getBody() != null) {
            acceptTree(function.getBody(), builder);
        }

        // Impliciete void-return als het blok niet beëindigd is
        if (!(methodSymbol.isAbstract()
                 || methodSymbol.hasFlag(Flags.NATIVE))
                        && !builder.currentBlockTerminated()) {
            if (returnType == IRType.VOID) {
                builder.emitReturn(null);
            }
        }

        scope.popScope();
        builder.endFunction();
        return null;
    }

    // -------------------------------------------------------
    // Statements
    // -------------------------------------------------------

    @Override
    public IRValue visitBlockStatement(BlockStatementTree blockStatement,
                                       IRBuilder param) {
        scope.pushScope();
        for (StatementTree stmt : blockStatement.getStatements()) {
            acceptTree(stmt, builder);
        }
        scope.popScope();
        return null;
    }

    @Override
    public IRValue visitReturnStatement(ReturnStatementTree returnStatement,
                                        IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                returnStatement.getLineNumber(),
                returnStatement.getColumnNumber()
        );

        ExpressionTree expr = returnStatement.getExpression();
        if (expr == null) {
            builder.emitReturn(null);
        } else {
            IRValue value = acceptTree(expr, builder);
            builder.emitReturn(value);
        }
        return null;
    }

    @Override
    public IRValue visitVariableDeclaratorStatement(
            VariableDeclaratorTree varDecl, IRBuilder param) {

        builder.setLocation(
                currentClassName + ".nabu",
                varDecl.getLineNumber(),
                varDecl.getColumnNumber()
        );

        // Type bepalen via TypeMirror op de type-expressie

        TypeMirror typeMirror = varDecl.getVariableType().getType();
        IRType irType = TypeMirrorToIRType.map(typeMirror);

        String name = varDecl.getName().getName();

        final var symbol = varDecl.getName().getSymbol();

        if (symbol.getKind() == ElementKind.LOCAL_VARIABLE) {
            // Alloca voor de variabele
            IRValue ptr = builder.emitAlloca(name, irType);
            scope.define(name, ptr);

            // Initialisator
            if (varDecl.getValue() != null) {
                IRValue initValue = acceptTree(varDecl.getValue(), builder);
                if (initValue != null) {
                    builder.emitStore(ptr, initValue);
                }
            }
        } else if (symbol.getKind() == ElementKind.PARAMETER) {
            //Ignore
        } else {
            IRValue initValue = varDecl.getValue() != null ? acceptTree(varDecl.getValue(), builder) : null;
            final var owner = symbol.getEnclosingElement().asType();
            final var ownerType = TypeMirrorToIRType.map(owner);

            builder.declareGlobal(name, irType, initValue, ownerType, symbol.isStatic());

            long fieldFlags = Flags.PRIVATE;
            if (symbol.isStatic()) {
                fieldFlags |= Flags.STATIC;
            }
            final var irField = IRField.field(
                    fieldFlags,
                    name,
                    irType,
                    null
            );
            final var fieldAnnotations = symbol.getAnnotationMirrors().stream()
                    .filter(a -> a instanceof CompoundAttribute)
                    .map(a -> (CompoundAttribute) a)
                    .toList();
            irField.setAnnotations(fieldAnnotations);

            final var fieldSignature = JvmSignatureBuilder.buildFieldSignature(typeMirror);
            if (fieldSignature != null) {
                irField.setGenericSignature(fieldSignature);
            }

            module.emitField(irField);
        }

        return null;
    }

    @Override
    public IRValue visitExpressionStatement(
            ExpressionStatementTree expressionStatement, IRBuilder param) {
        // Voer de expressie uit, gooi het resultaat weg
        acceptTree(expressionStatement.getExpression(), builder);

        if (expressionStatement.getExpression() instanceof MethodInvocationTree methodInvocationTree) {
            if (methodInvocationTree.getMethodType().getReturnType().getKind() != TypeKind.VOID) {
                builder.emitPop();
            }
        }

        return null;
    }

    @Override
    public IRValue visitIfStatement(IfStatementTree ifStatement,
                                    IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                ifStatement.getLineNumber(),
                ifStatement.getColumnNumber()
        );

        // Sla het huidige blok op vóór beginBlock de cursor verplaatst
        IRBasicBlock entryBlk = builder.currentBlock();

        IRBasicBlock thenBlk = builder.beginBlock("if.then");
        IRBasicBlock elseBlk = ifStatement.getElseStatement() != null
                ? builder.beginBlock("if.else") : null;
        IRBasicBlock mergeBlk = builder.beginBlock("if.merge");

        // Conditie in entry-blok
        builder.setCurrentBlock(entryBlk);
        IRValue cond = acceptTree(ifStatement.getExpression(), builder);
        builder.emitCondBranch(cond, thenBlk,
                elseBlk != null ? elseBlk : mergeBlk);

        // Then
        builder.setCurrentBlock(thenBlk);
        acceptTree(ifStatement.getThenStatement(), builder);
        if (!builder.currentBlockTerminated())
            builder.emitBranch(mergeBlk);

        // Else (optioneel)
        if (elseBlk != null) {
            builder.setCurrentBlock(elseBlk);
            acceptTree(ifStatement.getElseStatement(), builder);
            if (!builder.currentBlockTerminated())
                builder.emitBranch(mergeBlk);
        }

        builder.setCurrentBlock(mergeBlk);
        return null;
    }

    @Override
    public IRValue visitWhileStatement(WhileStatementTree whileStatement,
                                       IRBuilder param) {
        IRBasicBlock entryBlk = builder.currentBlock();
        IRBasicBlock condBlk = builder.beginBlock("while.cond");
        IRBasicBlock bodyBlk = builder.beginBlock("while.body");
        IRBasicBlock exitBlk = builder.beginBlock("while.exit");

        breakTargets.push(exitBlk);
        continueTargets.push(condBlk);
        registerLabeledContinueTargets(condBlk);

        builder.setCurrentBlock(entryBlk);
        builder.emitBranch(condBlk);

        // Conditie
        builder.setCurrentBlock(condBlk);
        IRValue cond = acceptTree(whileStatement.getCondition(), builder);
        builder.emitCondBranch(cond, bodyBlk, exitBlk);

        // Body
        builder.setCurrentBlock(bodyBlk);
        acceptTree(whileStatement.getBody(), builder);
        if (!builder.currentBlockTerminated())
            builder.emitBranch(condBlk);

        breakTargets.pop();
        continueTargets.pop();

        builder.setCurrentBlock(exitBlk);
        return null;
    }

    @Override
    public IRValue visitDoWhileStatement(DoWhileStatementTree doWhileStatement,
                                         IRBuilder param) {
        IRBasicBlock entryBlk = builder.currentBlock();
        IRBasicBlock bodyBlk = builder.beginBlock("dowhile.body");
        IRBasicBlock condBlk = builder.beginBlock("dowhile.cond");
        IRBasicBlock exitBlk = builder.beginBlock("dowhile.exit");

        breakTargets.push(exitBlk);
        continueTargets.push(condBlk);
        registerLabeledContinueTargets(condBlk);

        builder.setCurrentBlock(entryBlk);
        builder.emitBranch(bodyBlk);

        // Body
        builder.setCurrentBlock(bodyBlk);
        acceptTree(doWhileStatement.getBody(), builder);
        if (!builder.currentBlockTerminated())
            builder.emitBranch(condBlk);

        // Conditie
        builder.setCurrentBlock(condBlk);
        IRValue cond = acceptTree(doWhileStatement.getCondition(), builder);
        builder.emitCondBranch(cond, bodyBlk, exitBlk);

        breakTargets.pop();
        continueTargets.pop();

        builder.setCurrentBlock(exitBlk);
        return null;
    }

    @Override
    public IRValue visitForStatement(ForStatementTree forStatement,
                                     IRBuilder param) {
        IRBasicBlock entryBlk = builder.currentBlock();
        IRBasicBlock condBlk = builder.beginBlock("for.cond");
        IRBasicBlock bodyBlk = builder.beginBlock("for.body");
        IRBasicBlock updateBlk = builder.beginBlock("for.update");
        IRBasicBlock exitBlk = builder.beginBlock("for.exit");

        breakTargets.push(exitBlk);
        continueTargets.push(updateBlk);
        registerLabeledContinueTargets(updateBlk);

        // Init in entry-blok
        builder.setCurrentBlock(entryBlk);
        scope.pushScope();
        for (StatementTree init : forStatement.getForInit()) {
            acceptTree(init, builder);
        }
        builder.emitBranch(condBlk);

        // Conditie
        builder.setCurrentBlock(condBlk);
        if (forStatement.getCondition() != null) {
            IRValue cond = acceptTree(forStatement.getCondition(), builder);
            builder.emitCondBranch(cond, bodyBlk.label(), exitBlk.label());
        } else {
            // for (;;) — oneindige lus
            builder.emitBranch(bodyBlk);
        }

        // Body
        builder.setCurrentBlock(bodyBlk);
        acceptTree(forStatement.getStatement(), builder);
        if (!builder.currentBlockTerminated())
            builder.emitBranch(updateBlk);

        // Update
        builder.setCurrentBlock(updateBlk);
        for (StatementTree update : forStatement.getForUpdate()) {
            acceptTree(update, builder);
        }
        builder.emitBranch(condBlk);

        scope.popScope();

        breakTargets.pop();
        continueTargets.pop();

        builder.setCurrentBlock(exitBlk);
        return null;
    }

    // -------------------------------------------------------
    // Expressies
    // -------------------------------------------------------

    @Override
    public IRValue visitLiteralExpression(LiteralExpressionTree literal,
                                          IRBuilder param) {
        Object value = literal.getLiteral();

        return switch (literal.getLiteralKind()) {
            case INTEGER -> builder.constInt(((Number) value).longValue());
            case LONG -> builder.constInt(((Long) value), 64);
            case BOOLEAN -> builder.constBool((Boolean) value);
            case FLOAT -> IRValue.ofF32(((Float) value).doubleValue());
            case DOUBLE -> builder.constFloat((Double) value);
            case BYTE -> builder.constInt(((Byte) value).longValue(), 8);
            case SHORT -> builder.constInt(((Short) value).longValue(), 16);
            case CHAR -> builder.constInt(((Character) value), 16);
            case STRING -> builder.constString((String) value, TypeMirrorToIRType.toJvmDescriptor(literal.getType()));
            case NULL -> IRValue.nullPtr(IRType.I8);
            case CLASS -> IRValue.nullPtr(IRType.I8); // class-literal als opaque ptr
        };
    }

    @Override
    public IRValue visitBinaryExpression(BinaryExpressionTree binaryExpr,
                                         IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                binaryExpr.getLineNumber(),
                binaryExpr.getColumnNumber()
        );

        Tag tag = binaryExpr.getTag();

        if (tag == Tag.ASSIGN) {
            IRValue value = acceptTree(binaryExpr.getRight(), builder);

            // Sla de waarde op via de linker kant
            emitStoreBack(binaryExpr.getLeft(), value);
            return value;
        }

        // Compound assignments: uitvouwen naar load + op + store
        if (TagToIROp.isCompoundAssignment(tag)) {
            return emitCompoundAssignment(binaryExpr);
        }

        IRValue left = acceptTree(binaryExpr.getLeft(), builder);
        IRValue right = acceptTree(binaryExpr.getRight(), builder);
        Op op = TagToIROp.map(tag);

        return builder.emitBinaryOp(op, left, right);
    }

    @Override
    public IRValue visitUnaryExpression(UnaryExpressionTree unary,
                                        IRBuilder param) {
        Tag tag = unary.getTag();
        IRValue operand = acceptTree(unary.getExpression(), builder);

        return switch (tag) {
            case SUB -> {
                // Negatie: 0 - operand
                IRValue zero = builder.constInt(0);
                yield builder.emitBinaryOp(Op.SUB, zero, operand);
            }
            case NOT -> {
                // Logische negatie: operand == false (0)
                IRValue zero = builder.constBool(false);
                yield builder.emitBinaryOp(Op.EQ, operand, zero);
            }
            case POST_INC -> {
                // i++ : laad, verhoog, sla op, geef originele waarde terug
                IRValue one = builder.constInt(1);
                IRValue incremented = builder.emitBinaryOp(Op.ADD, operand, one);
                emitStoreBack(unary.getExpression(), incremented);
                yield operand; // post = geef originele waarde terug
            }
            case POST_DEC -> {
                IRValue one = builder.constInt(1);
                IRValue decremented = builder.emitBinaryOp(Op.SUB, operand, one);
                emitStoreBack(unary.getExpression(), decremented);
                yield operand;
            }
            default -> operand;
        };
    }

    @Override
    public IRValue visitIdentifier(IdentifierTree identifier, IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                identifier.getLineNumber(),
                identifier.getColumnNumber()
        );

        String name = identifier.getName();

        // this / super — geef de this-parameter terug
        if ("this".equals(name) || "super".equals(name)) {
            var local = scope.lookup("this");
            if (local.isPresent()) {
                IRValue ptr = local.get();
                if (ptr.type() instanceof IRType.Ptr ptrType && ptrType.jvmDescriptor() == null) {
                    return builder.emitLoad(ptr);
                }
            } else {
                return null;
            }
        }

        // Lokale variabele of parameter
        var local = scope.lookup(name);
        if (local.isPresent()) {
            IRValue ptr = local.get();
            if (ptr.type() instanceof IRType.Ptr ptrType) {
                if (ptrType.jvmDescriptor() == null) {
                    return builder.emitLoad(ptr);
                }
                return ptr;
            } else if (ptr instanceof IRValue.Temp temp) {
                return builder.emitLoad(temp);
            }
            return ptr;
        }

        // Symbool opzoeken via het element
        Element symbol = (identifier instanceof CIdentifierTree ci)
                ? ci.getSymbol() : null;

        if (symbol != null) {
            ElementKind kind = symbol.getKind();

            if (kind.isField()) {
                // Statisch veld
                if (symbol.isStatic()) {
                    String globalName = symbol.getEnclosingElement().getSimpleName()
                            + "_" + name;
                    var globalPtr = builder.lookup(globalName);
                    return builder.emitLoad(globalPtr);
                }
                // Instantieveld — laad via this
                IRValue thisVal = scope.lookup("this").orElse(null);
                if (thisVal != null) {
                    return emitFieldLoad(thisVal, symbol);
                }
            }

            // Klasse-naam of type-referentie — geen directe waarde
            if (kind.isDeclaredType()) {
                return null;
            }
        }

        return null;
    }

    @Override
    public IRValue visitFieldAccessExpression(
            FieldAccessExpressionTree fieldAccess, IRBuilder param) {

        builder.setLocation(
                currentClassName + ".nabu",
                fieldAccess.getLineNumber(),
                fieldAccess.getColumnNumber()
        );

        ExpressionTree selected = fieldAccess.getSelected();
        IdentifierTree field = fieldAccess.getField();

        Element fieldSymbol = (field instanceof CIdentifierTree ci)
                ? ci.getSymbol() : null;

        // Statisch veld (bijv. System.out)
        if (fieldSymbol != null && fieldSymbol.isStatic()) {
            String globalName = ((TypeElement) fieldSymbol.getEnclosingElement()).getQualifiedName()
                    + "_" + field.getName();
            // Declareer als extern als nog niet aanwezig
            IRType fieldType = TypeMirrorToIRType.map(
                    fieldSymbol.asType());
            builder.declareExternalGlobal(globalName, fieldType, fieldSymbol.isStatic());
            IRValue globalPtr = builder.lookup(globalName);

            return builder.emitLoad(TypeMirrorToIRType.map(field.getSymbol().asType()), globalPtr);
        } else if (fieldSymbol == null) {
            final var type = field.getType();
            final var descriptor = TypeMirrorToIRType.toJvmDescriptor(type);
            return new IRValue.ConstClass(
                    new IRType.Ptr(IRType.I8, descriptor)
            );
        }

        // Instantieveld
        if (selected != null) {
            IRValue obj = acceptTree(selected, builder);
            if (obj != null && fieldSymbol != null) {
                return emitFieldLoad(obj, fieldSymbol);
            }
        }

        return null;
    }

    @Override
    public IRValue visitMethodInvocation(MethodInvocationTree invocation,
                                         IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                invocation.getLineNumber(),
                invocation.getColumnNumber()
        );

        ExecutableType methodType = invocation.getMethodType();
        if (methodType == null) {
            return null;
        }

        final IRType returnType;

        if (methodType.getMethodSymbol().getReturnType() instanceof TypeVariable typeVariable) {
            returnType = TypeMirrorToIRType.mapReturnType(
                    typeVariable.getUpperBound());
        } else {
            returnType = TypeMirrorToIRType.mapReturnType(
                    methodType.getReturnType());
        }


        List<IRType> paramTypes = methodType.getParameterTypes().stream()
                .map(TypeMirrorToIRType::map)
                .toList();

        CallKind callKind = CallKindResolver.resolve(invocation);
        String methodName = CallKindResolver.resolveName(invocation);
        ExpressionTree target = CallKindResolver.resolveTarget(invocation);

        final var owner = invocation.getMethodType().getMethodSymbol()
                .getEnclosingElement();

        // Argumenten verwerken
        List<IRValue> args = new ArrayList<>();

        // Voor instantie-aanroepen: voeg 'this' toe als eerste argument
        if (callKind != CallKind.STATIC && target != null) {
            IRValue receiver = acceptTree(target, builder);
            if (receiver != null) args.add(receiver);
        } else if (callKind != CallKind.STATIC) {
            // Impliciete this
            scope.lookup("this").ifPresent(args::add);
        }

        for (int i = 0; i < invocation.getArguments().size(); i++) {
            ExpressionTree arg = invocation.getArguments().get(i);
            IRValue argVal = acceptTree(arg, builder);

            if (argVal instanceof IRValue.FunctionRef fnRef
                    && i < methodType.getParameterTypes().size()) {
                final var paramType = methodType.getParameterTypes().get(i);
                if (paramType instanceof io.github.potjerodekool.nabu.type.DeclaredType dt) {
                    final var classElement = (TypeElement) dt.asElement();
                    if (classElement.isFunctionalInterface()) {
                        argVal = wrapWithSamConversion(fnRef, classElement, dt);
                    }
                }
            }

            if (argVal != null) args.add(argVal);
        }

        // Volledig gekwalificeerde naam
        String ownerName = methodType.getOwner() != null
                ? methodType.getOwner().getQualifiedName() + "_"
                : "";
        String fullName = ownerName + methodName;

        return switch (callKind) {
            case STATIC, VIRTUAL, INTERFACE ->
                    builder.emitCall(callKind, fullName, returnType, paramTypes, args);
            case SPECIAL ->
                // Constructor of super — gebruik ook emitCall
                    builder.emitCall(callKind, fullName, returnType, paramTypes, args);
        };
    }

    @Override
    public IRValue visitNewClass(NewClassExpression newClass, IRBuilder param) {
        if (!builder.insideFunction()) {
            return null;
        }


        builder.setLocation(
                currentClassName + ".nabu",
                newClass.getLineNumber(),
                newClass.getColumnNumber()
        );

        TypeMirror classMirror = newClass.getName().getType();

        IRType objectType = TypeMirrorToIRType.map(classMirror);

        // Alloceer het object op de heap
        IRValue obj = builder.emitHeapAlloc("new_obj", objectType);

        // Roep de constructor aan
        String className = classMirror != null && classMirror.getClassName() != null
                ? classMirror.getClassName().replace('.', '_')
                : "unknown";
        String initName = className + "_init";

        List<IRValue> args = new ArrayList<>();

        /*
        if (newClass.getClassDeclaration() != null) {
            args.add(obj); // this
        }
        */

        for (ExpressionTree arg : newClass.getArguments()) {
            IRValue argVal = acceptTree(arg, builder);
            if (argVal != null) args.add(argVal);
        }

        final var paramTypes = args.stream()
                .map(IRValue::type)
                .toList();

        builder.emitCall(CallKind.SPECIAL, initName, IRType.VOID, paramTypes, args);
        return obj;
    }

    @Override
    public IRValue visitAssignment(AssignmentExpressionTree assignment,
                                   IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                assignment.getLineNumber(),
                assignment.getColumnNumber()
        );

        IRValue value = acceptTree(assignment.getRight(), builder);

        // Sla de waarde op via de linker kant
        emitStoreBack(assignment.getLeft(), value);
        return value;
    }

    @Override
    public IRValue visitCastExpression(CastExpressionTree cast, IRBuilder param) {
        IRValue source = acceptTree(cast.getExpression(), builder);
        IRType targetType = TypeMirrorToIRType.map(cast.getType());
        return builder.emitCast(source, targetType);
    }

    @Override
    public IRValue visitArrayAccess(ArrayAccessExpressionTree arrayAccess,
                                    IRBuilder param) {
        IRValue array = acceptTree(arrayAccess.getExpression(), builder);
        IRValue index = acceptTree(arrayAccess.getIndex(), builder);

        if (array != null && array.type() instanceof IRType.Ptr ptrType) {
            IRType elemType = ptrType.pointee();
            return builder.emitArrayLoad(array, index, elemType);
        }
        return null;
    }

    // -------------------------------------------------------
    // Hulpmethoden
    // -------------------------------------------------------

    /**
     * Slaat een waarde terug op in de variabele die door een expressie
     * wordt aangeduid (voor assignments en post-increment/decrement).
     */
    private void emitStoreBack(ExpressionTree target, IRValue value) {
        if (target instanceof IdentifierTree id) {
            String name = id.getName();
            var ptr = scope.lookup(name);
            if (ptr.isPresent()) {
                builder.emitStore(ptr.get(), value);
            }
        } else if (target instanceof FieldAccessExpressionTree fieldAccess) {
            // Veld-toewijzing
            ExpressionTree selected = fieldAccess.getSelected();
            IdentifierTree field = fieldAccess.getField();

            Element fieldSymbol = (field instanceof CIdentifierTree ci)
                    ? ci.getSymbol() : null;

            if (fieldSymbol != null && fieldSymbol.isStatic()) {
                String globalName = fieldSymbol.getEnclosingElement().getSimpleName()
                        + "_" + field.getName();

                IRValue globalPtr = resolveField(fieldSymbol);

                //IRValue globalPtr = builder.lookup(globalName);
                if (globalPtr != null) {
                    builder.emitStore(globalPtr, value);
                }
            } else if (selected != null) {
                IRValue obj = acceptTree(selected, builder);
                if (obj != null && fieldSymbol != null) {
                    emitFieldStore(obj, fieldSymbol, field, value);
                }
            }
        }
    }

    private IRValue.Named resolveField(final Element fieldSymbol) {
        return new IRValue.Named(
                fieldSymbol.getSimpleName(),
                TypeMirrorToIRType.map(fieldSymbol.asType()),
                TypeMirrorToIRType.map(fieldSymbol.getEnclosingElement().asType()),
                fieldSymbol.isStatic()
        );
    }

    /**
     * Emitteert compound assignments (+=, -=, etc.) als load + op + store.
     */
    private IRValue emitCompoundAssignment(BinaryExpressionTree binaryExpr) {
        IRValue current = acceptTree(binaryExpr.getLeft(), builder);
        IRValue right = acceptTree(binaryExpr.getRight(), builder);
        Op op = TagToIROp.compoundAssignmentOp(binaryExpr.getTag());
        IRValue result = builder.emitBinaryOp(op, current, right);
        emitStoreBack(binaryExpr.getLeft(), result);
        return result;
    }

    /**
     * Laadt een veld van een object.
     * Vereist GEP — veldindex wordt opgezocht via het Element.
     * Voorlopig: laad als opaque pointer (veldoffsets worden bepaald bij codegen).
     */
    private IRValue emitFieldLoad(IRValue obj, Element fieldSymbol) {
        final var ownerType = TypeMirrorToIRType.map(fieldSymbol.getEnclosingElement().asType());
        final var fieldIndex = computeFieldIndex(fieldSymbol);

        return new IRValue.Values(
                obj,
                new IRValue.Named(
                        fieldSymbol.getSimpleName(),
                        TypeMirrorToIRType.map(fieldSymbol.asType()),
                        ownerType,
                        fieldSymbol.isStatic(),
                        fieldIndex
                )
        );
    }

    /**
     * Slaat een waarde op in een veld van een object.
     */
    private void emitFieldStore(final IRValue obj,
                                final Element fieldSymbol,
                                final IdentifierTree field,
                                final IRValue value) {
        final var ownerType = TypeMirrorToIRType.map(fieldSymbol.getEnclosingElement().asType());
        final var fieldIndex = computeFieldIndex(fieldSymbol);

        final var named = new IRValue.Named(
                fieldSymbol.getSimpleName(),
                TypeMirrorToIRType.map(fieldSymbol.asType()),
                ownerType,
                fieldSymbol.isStatic(),
                fieldIndex
        );
        builder.emitStore(new IRValue.Values(obj, named), value);
    }

    private int computeFieldIndex(final Element fieldSymbol) {
        final var ownerElement = fieldSymbol.getEnclosingElement();
        if (ownerElement instanceof TypeElement typeElement) {
            int index = 0;
            for (final var enclosed : typeElement.getEnclosedElements()) {
                if (enclosed.getKind().isField()) {
                    if (enclosed.getSimpleName().equals(fieldSymbol.getSimpleName())) {
                        return index;
                    }
                    index++;
                }
            }
        }
        return -1;
    }

    @Override
    public IRValue visitInstanceOfExpression(final InstanceOfExpression instanceOfExpression, final IRBuilder param) {
        final var value = acceptTree(instanceOfExpression.getExpression(), param);
        return builder.emitInstanceOf(value, TypeMirrorToIRType.map(instanceOfExpression.getTypeExpression().getType()));
    }

    @Override
    public IRValue visitNewArray(final NewArrayExpression newArrayExpression, final IRBuilder param) {
        final var arrayType = newArrayExpression.getType();
        final IRType objectType = TypeMirrorToIRType.map(arrayType);
        final var dimensions = newArrayExpression.getDimensions();

        if (dimensions.isEmpty()) {
            return builder.emitAllocaArray(0, objectType);
        }

        final var firstDim = dimensions.getFirst();
        IRValue size = acceptTree(firstDim, builder);

        if (size == null && firstDim instanceof LiteralExpressionTree lit) {
            size = IRValue.ofI32(((Number) lit.getLiteral()).intValue());
        }

        return builder.emitAllocaArray(
                size instanceof IRValue.ConstInt ci ? (int) ci.value() : 0,
                objectType
        );
    }

    @Override
    public IRValue visitThrowStatement(final ThrowStatement throwStatement, final IRBuilder param) {
        final var expression = throwStatement.getExpression();
        final TypeMirror throwType;

        if (expression instanceof NewClassExpression newClassExpression) {
            throwType = newClassExpression.getName().getType();
        } else {
            throwType = expression.getType();
        }

        final var type = TypeMirrorToIRType.map(throwType);
        builder.emitThrow(type);
        return null;
    }

    @Override
    public IRValue visitParenthesizedExpression(final ParenthesizedExpression parenthesizedExpression, final IRBuilder param) {
        return acceptTree(parenthesizedExpression.getExpression(), param);
    }

    // -------------------------------------------------------
    // Break / Continue
    // -------------------------------------------------------

    @Override
    public IRValue visitBreakStatement(final BreakStatement breakStatement, final IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                breakStatement.getLineNumber(),
                breakStatement.getColumnNumber()
        );

        final var target = breakStatement.getTarget();
        IRBasicBlock targetBlock;

        if (target == null) {
            if (breakTargets.isEmpty()) {
                throw new RuntimeException(new CompileException("'break' buiten een lus of switch"));
            }
            targetBlock = breakTargets.peek();
        } else {
            String labelName = resolveLabelTarget(target);
            targetBlock = labeledBreakTargets.get(labelName);
            if (targetBlock == null) {
                throw new RuntimeException(new CompileException("Onbekend label voor break: " + labelName));
            }
        }

        builder.emitBranch(targetBlock);
        return null;
    }

    @Override
    public IRValue visitContinueStatement(final ContinueStatement continueStatement, final IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                continueStatement.getLineNumber(),
                continueStatement.getColumnNumber()
        );

        final var target = continueStatement.getTarget();
        IRBasicBlock targetBlock;

        if (target == null) {
            if (continueTargets.isEmpty()) {
                throw new RuntimeException(new CompileException("'continue' buiten een lus"));
            }
            targetBlock = continueTargets.peek();
        } else {
            String labelName = resolveLabelTarget(target);
            targetBlock = labeledContinueTargets.get(labelName);
            if (targetBlock == null) {
                throw new RuntimeException(new CompileException("Onbekend label voor continue: " + labelName));
            }
        }

        builder.emitBranch(targetBlock);
        return null;
    }

    // -------------------------------------------------------
    // Enhanced for
    // -------------------------------------------------------

    @Override
    public IRValue visitEnhancedForStatement(final EnhancedForStatementTree enhancedFor,
                                              final IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                enhancedFor.getLineNumber(),
                enhancedFor.getColumnNumber()
        );

        // Uitvouwen naar een gewone for-lus:
        //   var __arr = <collection>;
        //   for (int __i = 0; __i < __arr.length; __i++) {
        //       var <var> = __arr[__i];
        //       <body>
        //   }

        IRValue collection = acceptTree(enhancedFor.getExpression(), builder);
        if (collection == null) return null;

        IRBasicBlock entryBlk = builder.currentBlock();
        IRBasicBlock condBlk = builder.beginBlock("efor.cond");
        IRBasicBlock bodyBlk = builder.beginBlock("efor.body");
        IRBasicBlock updateBlk = builder.beginBlock("efor.update");
        IRBasicBlock exitBlk = builder.beginBlock("efor.exit");

        breakTargets.push(exitBlk);
        continueTargets.push(updateBlk);
        registerLabeledContinueTargets(updateBlk);

        // Array-variabele alloceren
        IRType collectionType = collection.type();
        IRValue arrayPtr;
        if (collectionType instanceof IRType.Ptr ptrType) {
            arrayPtr = builder.emitAlloca("__arr", ptrType);
            builder.emitStore(arrayPtr, collection);
        } else {
            arrayPtr = builder.emitAlloca("__arr", collectionType);
            builder.emitStore(arrayPtr, collection);
        }

        // Index-variabele
        IRValue indexPtr = builder.emitAlloca("__i", IRType.I32);
        builder.emitStore(indexPtr, IRValue.ofI32(0));

        builder.emitBranch(condBlk);

        // Conditie: __i < __arr.length
        builder.setCurrentBlock(condBlk);
        IRValue indexVal = builder.emitLoad(indexPtr);
        IRValue arrayVal = builder.emitLoad(arrayPtr);
        IRValue lengthVal = builder.emitArrayLength(arrayVal);

        IRValue cond = builder.emitBinaryOp(Op.LT, indexVal, lengthVal);
        builder.emitCondBranch(cond, bodyBlk, exitBlk);

        // Body
        builder.setCurrentBlock(bodyBlk);
        scope.pushScope();

        // Variabele laden
        VariableDeclaratorTree localVar = enhancedFor.getLocalVariable();
        TypeMirror varType = localVar.getVariableType().getType();
        IRType irVarType = TypeMirrorToIRType.map(varType);
        String varName = localVar.getName().getName();

        IRValue elemPtr = builder.emitAlloca(varName, irVarType);
        IRValue bodyArrayVal = builder.emitLoad(arrayPtr);
        IRValue elemVal = builder.emitArrayLoad(bodyArrayVal, indexVal, irVarType);
        builder.emitStore(elemPtr, elemVal);
        scope.define(varName, elemPtr);

        acceptTree(enhancedFor.getStatement(), builder);
        scope.popScope();

        if (!builder.currentBlockTerminated())
            builder.emitBranch(updateBlk);

        // Update: __i++
        builder.setCurrentBlock(updateBlk);
        IRValue curIdx = builder.emitLoad(indexPtr);
        IRValue one = IRValue.ofI32(1);
        IRValue newIdx = builder.emitBinaryOp(Op.ADD, curIdx, one);
        builder.emitStore(indexPtr, newIdx);
        builder.emitBranch(condBlk);

        breakTargets.pop();
        continueTargets.pop();

        builder.setCurrentBlock(exitBlk);
        return null;
    }

    // -------------------------------------------------------
    // Switch
    // -------------------------------------------------------

    @Override
    public IRValue visitSwitchStatement(final SwitchStatement switchStatement,
                                         final IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                switchStatement.getLineNumber(),
                switchStatement.getColumnNumber()
        );

        IRValue selector = acceptTree(switchStatement.getSelector(), builder);
        if (selector == null) return null;

        List<CaseStatement> cases = switchStatement.getCases();

        // Stap 1: Maak alle blokken aan
        IRBasicBlock entryBlk = builder.currentBlock();
        IRBasicBlock exitBlk = builder.beginBlock("switch.exit");

        breakTargets.push(exitBlk);

        List<IRBasicBlock> cmpBlocks = new ArrayList<>();
        List<IRBasicBlock> caseBodyBlocks = new ArrayList<>();
        IRBasicBlock defaultBlock = null;

        for (CaseStatement caseStmt : cases) {
            IRBasicBlock cmpBlk = builder.beginBlock("switch.cmp");
            cmpBlocks.add(cmpBlk);
            IRBasicBlock caseBlk = builder.beginBlock("switch.case");
            caseBodyBlocks.add(caseBlk);

            if (caseStmt.getLabels().stream().anyMatch(l -> l instanceof DefaultCaseLabel)) {
                defaultBlock = caseBlk;
            }
        }

        // Stap 2: Entry blok → eerste vergelijkingsblok
        builder.setCurrentBlock(entryBlk);
        builder.emitBranch(cmpBlocks.get(0));

        // Stap 3: Vergelijkingsblokken invullen
        for (int i = 0; i < cases.size(); i++) {
            CaseStatement caseStmt = cases.get(i);
            IRBasicBlock cmpBlk = cmpBlocks.get(i);
            IRBasicBlock caseBlk = caseBodyBlocks.get(i);
            IRBasicBlock nextCmpBlk = (i + 1 < cases.size()) ? cmpBlocks.get(i + 1) : null;

            builder.setCurrentBlock(cmpBlk);

            boolean isDefault = caseStmt.getLabels().stream()
                    .anyMatch(l -> l instanceof DefaultCaseLabel);

            if (isDefault) {
                // Default: spring altijd naar het case-blok
                builder.emitBranch(caseBlk);
            } else {
                // Vergelijkingen voor elke constante label
                IRValue match = null;
                for (CaseLabel label : caseStmt.getLabels()) {
                    if (label instanceof ConstantCaseLabel constLabel) {
                        IRValue caseValue = acceptTree(constLabel.getExpression(), builder);
                        if (caseValue != null) {
                            IRValue eq = builder.emitBinaryOp(Op.EQ, selector, caseValue);
                            match = (match == null)
                                    ? eq
                                    : builder.emitBinaryOp(Op.OR, match, eq);
                        }
                    }
                }

                if (match != null) {
                    builder.emitCondBranch(match, caseBlk,
                            nextCmpBlk != null ? nextCmpBlk : (defaultBlock != null ? defaultBlock : exitBlk));
                } else {
                    builder.emitBranch(nextCmpBlk != null ? nextCmpBlk :
                            (defaultBlock != null ? defaultBlock : exitBlk));
                }
            }
        }

        // Stap 4: Case-body blokken invullen
        for (int i = 0; i < cases.size(); i++) {
            CaseStatement caseStmt = cases.get(i);
            IRBasicBlock caseBlk = caseBodyBlocks.get(i);
            builder.setCurrentBlock(caseBlk);

            Tree body = caseStmt.getBody();
            if (body instanceof BlockStatementTree block) {
                acceptTree(block, builder);
            } else if (body != null) {
                acceptTree(body, builder);
            }

            // Fall-through: als het blok niet beëindigd is
            if (!builder.currentBlockTerminated()) {
                if (caseStmt.getCaseKind() == CaseStatement.CaseKind.RULE) {
                    // Rule-cases vallen niet door
                    builder.emitBranch(exitBlk);
                } else if (i + 1 < cases.size()) {
                    // Statement-case: val door naar het volgende case-blok
                    builder.emitBranch(caseBodyBlocks.get(i + 1));
                } else {
                    builder.emitBranch(exitBlk);
                }
            }
        }

        breakTargets.pop();
        builder.setCurrentBlock(exitBlk);
        return null;
    }

    // -------------------------------------------------------
    // Assert
    // -------------------------------------------------------

    @Override
    public IRValue visitAssertStatement(final AssertStatement assertStatement,
                                         final IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                assertStatement.getLineNumber(),
                assertStatement.getColumnNumber()
        );

        // Uitvouwen naar: if (!condition) throw new AssertionError(detail)

        IRBasicBlock entryBlk = builder.currentBlock();
        IRBasicBlock passBlk = builder.beginBlock("assert.pass");
        IRBasicBlock failBlk = builder.beginBlock("assert.fail");
        IRBasicBlock mergeBlk = builder.beginBlock("assert.merge");

        IRValue cond = acceptTree(assertStatement.getCondition(), builder);
        builder.emitCondBranch(cond, passBlk, failBlk);

        // Fail: gooi AssertionError
        builder.setCurrentBlock(failBlk);
        builder.emitThrow(new IRType.Ptr(IRType.I8, "Ljava/lang/AssertionError;"));
        if (!builder.currentBlockTerminated()) {
            builder.emitBranch(mergeBlk);
        }

        // Pass
        builder.setCurrentBlock(passBlk);
        builder.emitBranch(mergeBlk);

        builder.setCurrentBlock(mergeBlk);
        return null;
    }

    // -------------------------------------------------------
    // Yield
    // -------------------------------------------------------

    @Override
    public IRValue visitYieldStatement(final YieldStatement yieldStatement,
                                        final IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                yieldStatement.getLineNumber(),
                yieldStatement.getColumnNumber()
        );

        ExpressionTree expr = yieldStatement.getExpression();
        if (expr == null) {
            builder.emitReturn(null);
        } else {
            IRValue value = acceptTree(expr, builder);
            builder.emitReturn(value);
        }
        return null;
    }

    // -------------------------------------------------------
    // Labeled statement
    // -------------------------------------------------------

    @Override
    public IRValue visitLabeledStatement(final LabeledStatement labeledStatement,
                                          final IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                labeledStatement.getLineNumber(),
                labeledStatement.getColumnNumber()
        );

        String label = labeledStatement.getLabel();

        // We kunnen geen forward-reference maken naar blokken die nog niet bestaan.
        // Los dit op door een placeholder te registreren die later wordt opgelost.
        IRBasicBlock breakPlaceholder = builder.beginBlock("label." + label + ".break");
        labeledBreakTargets.put(label, breakPlaceholder);

        // Markeer als pending label zodat de volgende lus de continue target kan instellen
        pendingLabels.push(label);

        StatementTree body = labeledStatement.getStatement();
        acceptTree(body, builder);

        // Verwijder het pending label als de lus het niet heeft opgepakt
        pendingLabels.remove(label);
        labeledBreakTargets.remove(label);
        labeledContinueTargets.remove(label);

        return null;
    }

    // -------------------------------------------------------
    // Synchronized
    // -------------------------------------------------------

    @Override
    public IRValue visitSynchronizedStatement(final SynchronizedStatement synchronizedStatement,
                                               final IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                synchronizedStatement.getLineNumber(),
                synchronizedStatement.getColumnNumber()
        );

        // Uitvouwen naar: monitorenter(__lock); <body>; monitorexit(__lock);

        IRValue lockObj = acceptTree(synchronizedStatement.getExpression(), builder);
        if (lockObj == null) return null;

        // monitorenter
        builder.emitMonitorEnter(lockObj);

        // Body
        acceptTree(synchronizedStatement.getBody(), builder);

        // monitorexit
        builder.emitMonitorExit(lockObj);

        return null;
    }

    // -------------------------------------------------------
    // Try-catch-finally
    // -------------------------------------------------------

    @Override
    public IRValue visitTryStatement(final TryStatementTree tryStatement,
                                      final IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                tryStatement.getLineNumber(),
                tryStatement.getColumnNumber()
        );

        boolean hasFinally = tryStatement.getFinalizer() != null;

        IRBasicBlock prevBlk = builder.currentBlock();
        IRBasicBlock tryStartBlk = builder.beginBlock("try.body");
        IRBasicBlock tryEndBlk = builder.beginBlock("try.end");

        // Branch vanuit het huidige blok naar de try-body
        if (prevBlk != null && !prevBlk.isTerminated()) {
            builder.setCurrentBlock(prevBlk);
            builder.emitBranch(tryStartBlk);
        }

        // Try body
        builder.setCurrentBlock(tryStartBlk);
        acceptTree(tryStatement.getBody(), builder);
        if (!builder.currentBlockTerminated()) {
            if (hasFinally) {
                builder.emitBranch(tryEndBlk);
            } else {
                builder.emitBranch(tryEndBlk);
            }
        }

        // Catch handlers
        List<CatchTree> catchers = tryStatement.getCatchers();
        for (int i = 0; i < catchers.size(); i++) {
            CatchTree catcher = catchers.get(i);
            IRBasicBlock handlerBlk = builder.beginBlock("try.catch." + i);

            // TryCatchRegion metadata
            String exType = null;
            VariableDeclaratorTree catchVar = catcher.getVariable();
            if (catchVar != null && catchVar.getVariableType() != null) {
                TypeMirror exTypeMirror = catchVar.getVariableType().getType();
                if (exTypeMirror != null) {
                    exType = exTypeMirror.getClassName();
                }
            }

            tryCatchRanges.add(new TryCatchRange(
                    tryStartBlk.label(),
                    tryEndBlk.label(),
                    handlerBlk.label(),
                    exType
            ));

            // Emit TryCatchRegion instruction at start of handler block
            builder.setCurrentBlock(handlerBlk);
            handlerBlk.add(0, new IRInstruction.TryCatchRegion(
                    tryStartBlk.label(),
                    tryEndBlk.label(),
                    handlerBlk.label(),
                    exType,
                    builder.currentLocation()
            ));

            // Catch body
            scope.pushScope();
            acceptTree(catcher.getVariable(), builder);
            acceptTree(catcher.getBody(), builder);
            scope.popScope();

            if (!builder.currentBlockTerminated()) {
                builder.emitBranch(tryEndBlk);
            }
        }

        builder.setCurrentBlock(tryEndBlk);
        return null;
    }

    // -------------------------------------------------------
    // Lambda
    // -------------------------------------------------------

    @Override
    public IRValue visitLambdaExpression(final LambdaExpressionTree lambdaExpression,
                                         final IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                lambdaExpression.getLineNumber(),
                lambdaExpression.getColumnNumber()
        );

        ExecutableType methodType = lambdaExpression.getLambdaMethodType();
        if (methodType == null) return null;

        // LambdaToMethod heeft al een static method + Function aangemaakt
        // voor deze lambda. We verwijzen daar naar ipv een duplicaat te maken.
        final var methodSymbol = methodType.getMethodSymbol();
        final String lambdaMethodName = methodSymbol.getSimpleName();
        final String fnName = currentClassName + "_" + lambdaMethodName;

        IRType returnType = TypeMirrorToIRType.mapReturnType(methodType.getReturnType());
        List<IRType> paramTypes = methodType.getParameterTypes().stream()
                .map(TypeMirrorToIRType::map)
                .toList();
        IRType.Function fnType = new IRType.Function(returnType, paramTypes);

        final var allParamNames = methodSymbol.getParameters().stream()
                .map(p -> p.getSimpleName().toString())
                .toList();

        return new IRValue.FunctionRef(fnName, fnType, null, null, null, null, allParamNames);
    }

    private IRValue.FunctionRef wrapWithSamConversion(final IRValue.FunctionRef fnRef,
                                                      final TypeElement functionalInterface,
                                                      final io.github.potjerodekool.nabu.type.DeclaredType paramType) {
        final var samMethod = (ExecutableElement) functionalInterface.findFunctionalMethod();
        if (samMethod == null) return fnRef;

        final var samDescriptor = TypeMirrorToIRType.toJvmDescriptor(samMethod.asType());
        final var samParamCount = samMethod.getParameters().size();

        final var instantiatedDescriptor = createInstantiatedSamDescriptor(samMethod, paramType);
        final var samMethodName = samMethod.getSimpleName().toString();

        final var capturedVarCount = fnRef.fnType().paramTypes().size() - samParamCount;
        final var capturedVarNames = new ArrayList<String>();
        final var sourceNames = fnRef.capturedVarNames();
        for (int i = 0; i < capturedVarCount; i++) {
            if (sourceNames != null && i < sourceNames.size()) {
                capturedVarNames.add(sourceNames.get(i));
            } else {
                capturedVarNames.add("%" + i);
            }
        }

        final var interfaceInternalName = "L" + functionalInterface.getQualifiedName().replace('.', '/') + ";";

        return new IRValue.FunctionRef(
                fnRef.name(),
                fnRef.fnType(),
                new IRType.Ptr(IRType.I8, interfaceInternalName),
                samMethodName,
                samDescriptor,
                instantiatedDescriptor,
                capturedVarNames
        );
    }

    private String createInstantiatedSamDescriptor(final ExecutableElement samMethod,
                                                   final io.github.potjerodekool.nabu.type.DeclaredType paramType) {
        final var sb = new StringBuilder("(");
        final var typeArgs = paramType.getTypeArguments();

        for (int i = 0; i < samMethod.getParameters().size(); i++) {
            final var paramType2 = samMethod.getParameters().get(i).asType();
            if (paramType2 instanceof io.github.potjerodekool.nabu.type.TypeVariable tv) {
                final var bounds = tv.getUpperBound();
                sb.append(TypeMirrorToIRType.toJvmDescriptor(bounds));
            } else {
                sb.append(TypeMirrorToIRType.toJvmDescriptor(paramType2));
            }
        }
        sb.append(")");
        sb.append(TypeMirrorToIRType.toJvmDescriptor(samMethod.getReturnType()));
        return sb.toString();
    }

    // -------------------------------------------------------
    // Member reference
    // -------------------------------------------------------

    @Override
    public IRValue visitMemberReference(final MemberReference memberReference,
                                        final IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                memberReference.getLineNumber(),
                memberReference.getColumnNumber()
        );

        // Member references worden vertaald naar een functiereferentie
        // Bijv. Foo::bar → een verwijzing naar de methode Foo_bar

        ExpressionTree expr = memberReference.getExpression();
        String name = memberReference.getName();

        // Bepaal de owner-klasse
        String ownerName = "";
        if (expr != null) {
            TypeMirror exprType = expr.getType();
            if (exprType != null) {
                ownerName = exprType.getClassName();
            }
        }

        String qualifiedName = ownerName.replace('.', '_') + "_" + name;

        // Zoek het methodetype op
        TypeMirror refType = memberReference.getType();
        if (refType instanceof ExecutableType execType) {
            IRType returnType = TypeMirrorToIRType.mapReturnType(execType.getReturnType());
            List<IRType> paramTypes = execType.getParameterTypes().stream()
                    .map(TypeMirrorToIRType::map)
                    .toList();
            IRType.Function fnType = new IRType.Function(returnType, paramTypes);
            return builder.functionRef(qualifiedName, fnType);
        }

        return null;
    }

    // -------------------------------------------------------
    // Annotation (runtime — geen IR-waarde)
    // -------------------------------------------------------

    @Override
    public IRValue visitAnnotation(final AnnotationTree annotationTree,
                                   final IRBuilder param) {
        return null;
    }

    // -------------------------------------------------------
    // Wildcard (type-level, geen runtime-waarde)
    // -------------------------------------------------------

    @Override
    public IRValue visitWildCardExpression(final WildcardExpressionTree wildCardExpression,
                                           final IRBuilder param) {
        return null;
    }

    // -------------------------------------------------------
    // Type pattern (instanceof + binding)
    // -------------------------------------------------------

    @Override
    public IRValue visitTypePattern(final TypePattern typePattern,
                                    final IRBuilder param) {
        // Type patterns worden al behandeld door visitInstanceOfExpression
        // en visitSwitchStatement. Hier is de pattern zelf — geen runtime-waarde.
        return null;
    }

    // -------------------------------------------------------
    // Pattern case label (switch met patronen)
    // -------------------------------------------------------

    @Override
    public IRValue visitPatternCaseLabel(final PatternCaseLabel patternCaseLabel,
                                         final IRBuilder param) {
        return null;
    }

    // -------------------------------------------------------
    // Module directives (geen runtime-IR)
    // -------------------------------------------------------

    @Override
    public IRValue visitModuleDeclaration(final ModuleDeclaration moduleDeclaration,
                                          final IRBuilder param) {
        return null;
    }

    @Override
    public IRValue visitRequires(final RequiresTree requiresTree,
                                 final IRBuilder param) {
        return null;
    }

    @Override
    public IRValue visitExports(final ExportsTree exportsTree,
                                final IRBuilder param) {
        return null;
    }

    @Override
    public IRValue visitOpens(final OpensTree opensTree,
                              final IRBuilder param) {
        return null;
    }

    @Override
    public IRValue visitUses(final UsesTree usesTree,
                             final IRBuilder param) {
        return null;
    }

    @Override
    public IRValue visitProvides(final ProvidesTree providesTree,
                                 final IRBuilder param) {
        return null;
    }

    // -------------------------------------------------------
    // Hulpmethoden
    // -------------------------------------------------------

    private String resolveLabelTarget(final Tree target) {
        if (target instanceof IdentifierTree id) {
            return id.getName();
        }
        return target.toString();
    }

    /**
     * Registreert het huidige blok als continue target voor een pending label.
     * Wordt aangeroepen door lus-bezoekers (while, do-while, for, enhanced-for).
     */
    private void registerLabeledContinueTargets(final IRBasicBlock continueBlock) {
        for (String label : pendingLabels) {
            labeledContinueTargets.put(label, continueBlock);
        }
    }

    private boolean isJavaLangObject(TypeMirror type) {
        if (type instanceof io.github.potjerodekool.nabu.type.DeclaredType declared) {
            if (declared.asElement() instanceof TypeElement te) {
                return "java.lang.Object".equals(te.getQualifiedName());
            }
        }
        return false;
    }
}
