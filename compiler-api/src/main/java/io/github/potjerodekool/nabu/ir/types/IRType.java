package io.github.potjerodekool.nabu.ir.types;

import java.util.List;
import java.util.stream.Collectors;

public sealed interface IRType permits IRType.Array, IRType.Bool, IRType.CustomType, IRType.Float, IRType.Function, IRType.Int, IRType.Ptr, IRType.Struct, IRType.Void {

    //Byte = 8
    //Short|Character = 16
    //Integer = 32
    //Long = 64
    record Int(int bits) implements IRType {
        @Override public String toString() { return "i" + bits; }
    }

    record Float(int bits) implements IRType {
        @Override public String toString() { return bits == 32 ? "float" : "double"; }
    }

    record Bool() implements IRType {
        @Override public String toString() { return "i1"; }
    }

    record Ptr(IRType pointee) implements IRType {
        @Override public String toString() { return "ptr"; }
    }

    record Array(IRType elem, int size) implements IRType {
        @Override public String toString() { return "[" + size + " x " + elem + "]"; }
    }

    record Void() implements IRType {
        @Override public String toString() { return "void"; }
    }

    record Function(IRType returnType, List<IRType> paramTypes) implements IRType {
        public Ptr ptr() { return new Ptr(this); }

        public String signature() {
            String params = paramTypes.stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
            return "(" + params + ") -> " + returnType;
        }

        @Override public String toString() { return signature(); }
    }

    record Struct(List<IRType> fields) implements IRType {
        @Override public String toString() {
            return fields.stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(", ", "{ ", " }"));
        }
    }

    record CustomType(String name) implements IRType {
    }

    // Veelgebruikte constanten
    IRType I8   = new Int(8);
    IRType I16  = new Int(16);
    IRType I32  = new Int(32);
    IRType I64  = new Int(64);
    IRType F32  = new Float(32);
    IRType F64  = new Float(64);
    IRType BOOL = new Bool();
    IRType VOID = new Void();

    static Function fn(IRType ret, IRType... params) {
        return new Function(ret, List.of(params));
    }
}
