package io.github.potjerodekool.nabu.compiler.backend.ir.optimize;

import io.github.potjerodekool.nabu.compiler.ir.IRFunction;
import io.github.potjerodekool.nabu.compiler.ir.IRModule;
import io.github.potjerodekool.nabu.compiler.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.compiler.ir.types.IRType;
import io.github.potjerodekool.nabu.compiler.ir.values.IRValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * IR Type Checker: valideert de type-geldigheid van het IR.
 *
 * Controles:
 *   - BinaryOp: linker en rechter operand moeten compatibel zijn
 *   - Phi: alle incoming waarden moeten hetzelfde type hebben als het resultaat
 *   - Load: pointer moet een Ptr-type zijn
 *   - Store: waarde moet compatibel zijn met het pointee-type
 *   - Return: waarde moet compatibel zijn met het functie-returntype
 *   - Call: argumenten moeten compatibel zijn met de parameter types
 *   - Move: bron en doel moeten hetzelfde type hebben
 *   - Alloca: het alloca-type moet geldig zijn
 *
 * Geeft een lijst van type-fouten terug.
 */
public class TypeChecker {

    /**
     * Resultaat van een type-check: een lijst van foutmeldingen.
     */
    public record TypeCheckResult(List<String> errors) {
        public boolean isOk() {
            return errors.isEmpty();
        }
    }

    /**
     * Controleert alle functies in de module.
     */
    public static TypeCheckResult check(final IRModule module) {
        final List<String> errors = new ArrayList<>();

        for (final var function : module.functions()) {
            if (!function.isExternal()) {
                errors.addAll(checkFunction(function).errors());
            }
        }

        return new TypeCheckResult(errors);
    }

    /**
     * Controleert één functie.
     */
    public static TypeCheckResult checkFunction(final IRFunction function) {
        final List<String> errors = new ArrayList<>();
        final var typeMap = buildTypeMap(function);

        // Controleer functie-returntype
        for (final var block : function.blocks()) {
            for (final var instr : block.instructions()) {
                if (instr instanceof IRInstruction.Return ret && ret.value() != null) {
                    final var valueType = resolveType(ret.value(), typeMap);
                    if (valueType != null && function.returnType != null) {
                        final var common = TypeInference.commonType(valueType, function.returnType);
                        if (common == null || !common.equals(function.returnType)) {
                            errors.add(String.format(
                                    "Return-waarde type %s is niet compatibel met functie-returntype %s in blok %s",
                                    valueType, function.returnType, block.label()));
                        }
                    }
                }

                checkInstruction(instr, typeMap, block.label(), errors);
            }
        }

        return new TypeCheckResult(errors);
    }

    private static void checkInstruction(final IRInstruction instr,
                                          final Map<String, IRType> typeMap,
                                          final String blockLabel,
                                          final List<String> errors) {
        switch (instr) {
            case IRInstruction.BinaryOp op -> checkBinaryOp(op, typeMap, blockLabel, errors);
            case IRInstruction.Phi phi -> checkPhi(phi, typeMap, blockLabel, errors);
            case IRInstruction.Load ld -> checkLoad(ld, typeMap, blockLabel, errors);
            case IRInstruction.Store st -> checkStore(st, typeMap, blockLabel, errors);
            case IRInstruction.Move move -> checkMove(move, typeMap, blockLabel, errors);
            case IRInstruction.Call call -> checkCall(call, typeMap, blockLabel, errors);
            case IRInstruction.Alloca alloca -> checkAlloca(alloca, blockLabel, errors);
            default -> {}
        }
    }

    private static void checkBinaryOp(final IRInstruction.BinaryOp op,
                                       final Map<String, IRType> typeMap,
                                       final String blockLabel,
                                       final List<String> errors) {
        final var leftType = resolveType(op.left(), typeMap);
        final var rightType = resolveType(op.right(), typeMap);
        final var resultType = resolveType(op.result(), typeMap);

        if (leftType == null || rightType == null) {
            errors.add(String.format(
                    "BinaryOp %s heeft onbekende operand-type(s) in blok %s",
                    op.op(), blockLabel));
            return;
        }

        // Vergelijkingsoperaties produceren Bool
        if (isComparison(op.op())) {
            if (resultType != null && !(resultType instanceof IRType.Bool)) {
                errors.add(String.format(
                        "BinaryOp %s resultaat-type is %s, verwacht Bool in blok %s",
                        op.op(), resultType, blockLabel));
            }
            return;
        }

        // Arithmetic: operands moeten compatibel zijn
        if (TypeInference.commonType(leftType, rightType) == null) {
            errors.add(String.format(
                    "BinaryOp %s operands %s en %s zijn niet compatibel in blok %s",
                    op.op(), leftType, rightType, blockLabel));
        }
    }

    private static void checkPhi(final IRInstruction.Phi phi,
                                  final Map<String, IRType> typeMap,
                                  final String blockLabel,
                                  final List<String> errors) {
        final var resultType = resolveType(phi.result(), typeMap);

        for (final var incoming : phi.incomingValues()) {
            final var incomingType = resolveType(incoming.value(), typeMap);

            if (incomingType == null) {
                errors.add(String.format(
                        "Phi incoming waarde heeft onbekend type in blok %s",
                        blockLabel));
                continue;
            }

            if (resultType != null && TypeInference.commonType(resultType, incomingType) == null) {
                errors.add(String.format(
                        "Phi incoming type %s is niet compatibel met resultaat type %s in blok %s",
                        incomingType, resultType, blockLabel));
            }
        }
    }

    private static void checkLoad(final IRInstruction.Load ld,
                                   final Map<String, IRType> typeMap,
                                   final String blockLabel,
                                   final List<String> errors) {
        final var ptrType = resolveType(ld.ptr(), typeMap);

        if (ptrType == null) {
            errors.add(String.format(
                    "Load pointer heeft onbekend type in blok %s", blockLabel));
            return;
        }

        if (!(ptrType instanceof IRType.Ptr)) {
            errors.add(String.format(
                    "Load pointer type is %s, verwacht Ptr in blok %s",
                    ptrType, blockLabel));
        }
    }

    private static void checkStore(final IRInstruction.Store st,
                                    final Map<String, IRType> typeMap,
                                    final String blockLabel,
                                    final List<String> errors) {
        final var ptrType = resolveType(st.ptr(), typeMap);
        final var valueType = resolveType(st.value(), typeMap);

        if (ptrType == null || valueType == null) {
            errors.add(String.format(
                    "Store heeft onbekende type(s) in blok %s", blockLabel));
            return;
        }

        if (ptrType instanceof IRType.Ptr ptr && ptr.pointee() != null) {
            if (TypeInference.commonType(ptr.pointee(), valueType) == null) {
                errors.add(String.format(
                        "Store waarde type %s is niet compatibel met pointee type %s in blok %s",
                        valueType, ptr.pointee(), blockLabel));
            }
        }
    }

    private static void checkMove(final IRInstruction.Move move,
                                   final Map<String, IRType> typeMap,
                                   final String blockLabel,
                                   final List<String> errors) {
        final var resultType = resolveType(move.result(), typeMap);
        final var valueType = resolveType(move.value(), typeMap);

        if (resultType != null && valueType != null) {
            if (TypeInference.commonType(resultType, valueType) == null) {
                errors.add(String.format(
                        "Move type mismatch: %s → %s in blok %s",
                        valueType, resultType, blockLabel));
            }
        }
    }

    private static void checkCall(final IRInstruction.Call call,
                                   final Map<String, IRType> typeMap,
                                   final String blockLabel,
                                   final List<String> errors) {
        final var paramTypes = call.paramTypes();
        final var args = call.args();

        if (paramTypes.size() != args.size()) {
            errors.add(String.format(
                    "Call naar %s heeft %d argumenten maar %d parameters verwacht in blok %s",
                    call.function(), args.size(), paramTypes.size(), blockLabel));
            return;
        }

        for (int i = 0; i < args.size(); i++) {
            final var argType = resolveType(args.get(i), typeMap);
            final var paramType = paramTypes.get(i);

            if (argType != null && TypeInference.commonType(argType, paramType) == null) {
                errors.add(String.format(
                        "Call argument %d type %s is niet compatibel met parameter type %s in blok %s",
                        i, argType, paramType, blockLabel));
            }
        }
    }

    private static void checkAlloca(final IRInstruction.Alloca alloca,
                                     final String blockLabel,
                                     final List<String> errors) {
        final var resultType = alloca.result().type();

        if (resultType instanceof IRType.Ptr ptr) {
            if (ptr.pointee() == null) {
                errors.add(String.format(
                        "Alloca pointer heeft geen pointee type in blok %s", blockLabel));
            }
        } else {
            errors.add(String.format(
                    "Alloca resultaat type is %s, verwacht Ptr in blok %s",
                    resultType, blockLabel));
        }
    }

    private static boolean isComparison(final IRInstruction.BinaryOp.Op op) {
        return switch (op) {
            case EQ, NEQ, LT, LTE, GT, GTE -> true;
            default -> false;
        };
    }

    private static Map<String, IRType> buildTypeMap(final IRFunction function) {
        final Map<String, IRType> typeMap = new HashMap<>();

        for (final var param : function.params) {
            if (param instanceof IRValue.Temp temp) {
                typeMap.put(temp.name(), temp.type());
            } else if (param instanceof IRValue.Named named) {
                typeMap.put(named.name(), named.type());
            }
        }

        for (final var block : function.blocks()) {
            for (final var instr : block.instructions()) {
                final var result = instr.result();
                if (result != null) {
                    if (result instanceof IRValue.Temp temp) {
                        typeMap.putIfAbsent(temp.name(), temp.type());
                    } else if (result instanceof IRValue.Named named) {
                        typeMap.putIfAbsent(named.name(), named.type());
                    }
                }
            }
        }

        return typeMap;
    }

    private static IRType resolveType(final IRValue value,
                                       final Map<String, IRType> typeMap) {
        if (value == null) return null;
        if (value instanceof IRValue.ConstInt c) return c.type();
        if (value instanceof IRValue.ConstFloat c) return c.type();
        if (value instanceof IRValue.ConstBool c) return c.type();
        if (value instanceof IRValue.ConstString c) return c.type();
        if (value instanceof IRValue.ConstNull c) return c.type();
        if (value instanceof IRValue.ConstUndef c) return c.type();
        if (value instanceof IRValue.Temp temp) {
            return typeMap.getOrDefault(temp.name(), temp.type());
        }
        if (value instanceof IRValue.Named named) {
            return typeMap.getOrDefault(named.name(), named.type());
        }
        return value.type();
    }
}
