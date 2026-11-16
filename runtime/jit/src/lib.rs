//! A method JIT for the bytecode interpreter of Nabu (runtime/nabu_interpreter.c): the JVM bytecode of a hot static
//! method that only computes with int, long, float and double (loops, branches, calls of other such methods) becomes
//! machine code with Cranelift. The interpreter loads this library with dlopen and calls `nabu_jit_init` once, then
//! `nabu_jit_compile` for each method that has been called often enough; a method this library does not understand gets
//! NULL and stays interpreted.
//!
//! A compiled method is an `extern "C" fn(args: *const i64) -> i64`: the arguments come one per value (an int sign
//! extended, a float as its 32 bits, a double as its 64 bits) and the result goes back the same way.

use std::collections::{BTreeMap, BTreeSet};
use std::ffi::{c_void, CStr};
use std::os::raw::{c_char, c_int};
use std::sync::Mutex;

use cranelift_codegen::ir::condcodes::{FloatCC, IntCC};
use cranelift_codegen::ir::{types, AbiParam, BlockArg, InstBuilder, MemFlagsData, Signature, StackSlotData, StackSlotKind, Type, Value};
use cranelift_codegen::isa::CallConv;
use cranelift_codegen::settings::{self, Configurable};
use cranelift_frontend::{FunctionBuilder, FunctionBuilderContext, Variable};
use cranelift_jit::{JITBuilder, JITModule};
use cranelift_module::{default_libcall_names, Module};

/// What the interpreter offers to the compiler and to the compiled code (a C struct, filled in by the interpreter).
#[repr(C)]
#[derive(Clone, Copy)]
pub struct Host {
    pub cp_int: extern "C" fn(cp: *mut c_void, idx: c_int, out: *mut i32) -> c_int,
    pub cp_long: extern "C" fn(cp: *mut c_void, idx: c_int, out: *mut i64) -> c_int,
    pub cp_float: extern "C" fn(cp: *mut c_void, idx: c_int, out: *mut f32) -> c_int,
    pub cp_double: extern "C" fn(cp: *mut c_void, idx: c_int, out: *mut f64) -> c_int,
    /// The method that an invokestatic names (an opaque pointer of the interpreter) and its descriptor.
    pub resolve_static: extern "C" fn(cp: *mut c_void, idx: c_int, method: *mut *mut c_void, desc: *mut c_char, cap: c_int) -> c_int,
    /// Calls a method from compiled code; non-zero when it left an exception pending.
    pub call: extern "C" fn(method: *mut c_void, args: *const i64, nargs: c_int, result: *mut i64) -> c_int,
    /// Makes an ArithmeticException pending (division by zero).
    pub throw_arith: extern "C" fn(),
    /// The address of the interpreter's pending exception (non-null after a call that threw).
    pub pending: *const *mut c_void,
    /// A field named by a getfield/putfield (is_static = 0: out = byte offset in the object) or getstatic/putstatic
    /// (out = address of its 64-bit slot); `kind` gets the first character of the field descriptor. 0 when unknown.
    pub resolve_field: extern "C" fn(cp: *mut c_void, idx: c_int, is_static: c_int, out: *mut i64, kind: *mut c_char) -> c_int,
    /// Makes an exception pending: 1 NullPointerException, 2 ArrayIndexOutOfBoundsException.
    pub throw_exc: extern "C" fn(kind: c_int),
    /// newarray: the array object (length at 0, elements from byte 8) of a primitive type code and a length.
    pub new_array: extern "C" fn(atype: c_int, count: c_int) -> *mut c_void,
    /// The method named by an invokestatic (0xb8), invokespecial (0xb7) or invokevirtual (0xb6) of a class of the program,
    /// with its descriptor; 0 when it is not one that can be compiled.
    pub resolve_invoke: extern "C" fn(cp: *mut c_void, idx: c_int, op: c_int, method: *mut *mut c_void, desc: *mut c_char, cap: c_int) -> c_int,
    /// invokevirtual from compiled code: the receiver is args[0]; non-zero when an exception is pending.
    pub call_virtual: extern "C" fn(cp: *mut c_void, idx: c_int, args: *const i64, nargs: c_int, result: *mut i64) -> c_int,
    /// invokeinterface from compiled code: like call_virtual, a closure or proxy as receiver goes through the interpreter.
    pub call_interface: extern "C" fn(cp: *mut c_void, idx: c_int, args: *const i64, nargs: c_int, result: *mut i64) -> c_int,
    /// An invoke that the interpreter does (JDK methods, natives): the receiver, if any, is args[0].
    pub call_synth: extern "C" fn(cp: *mut c_void, idx: c_int, op: c_int, args: *const i64, nargs: c_int, result: *mut i64) -> c_int,
    /// `new`: a fresh object of the class.
    pub new_object: extern "C" fn(cp: *mut c_void, idx: c_int) -> *mut c_void,
    /// The String or Class constant of an ldc (made once, at compile time); NULL for another constant.
    pub ldc_ref: extern "C" fn(cp: *mut c_void, idx: c_int) -> *mut c_void,
    /// athrow: makes the object the pending exception.
    pub throw_obj: extern "C" fn(obj: *mut c_void),
    /// Whether the pending exception is caught by the catch type at a constant pool index (0 catches everything).
    pub exc_match: extern "C" fn(cp: *mut c_void, type_idx: c_int) -> c_int,
    /// The pending exception, which is no longer pending afterwards.
    pub exc_take: extern "C" fn() -> *mut c_void,
    /// getstatic / putstatic of a field the interpreter handles itself (JDK classes, System.out): non-zero when an exception is pending.
    pub get_static: extern "C" fn(cp: *mut c_void, idx: c_int, result: *mut i64) -> c_int,
    pub put_static: extern "C" fn(cp: *mut c_void, idx: c_int, value: i64) -> c_int,
    /// instanceof of an object against the class at a constant pool index.
    pub instance_of: extern "C" fn(cp: *mut c_void, idx: c_int, obj: *mut c_void) -> c_int,
}

struct Jit {
    module: JITModule,
    host: Host,
}

// The module holds raw pointers to the generated code; it is only used under the lock.
unsafe impl Send for Jit {}

static JIT: Mutex<Option<Jit>> = Mutex::new(None);

#[derive(Clone, Copy, PartialEq, Eq, Debug, PartialOrd, Ord)]
enum Ty {
    I,
    J,
    F,
    D,
    A,
}

fn clty(t: Ty) -> Type {
    match t {
        Ty::I => types::I32,
        Ty::J | Ty::A => types::I64,
        Ty::F => types::F32,
        Ty::D => types::F64,
    }
}

/// The type of a value: a reference is an i64 that was marked as one.
fn tov(b: &FunctionBuilder, refs: &BTreeSet<Value>, v: Value) -> Ty {
    if refs.contains(&v) {
        Ty::A
    } else {
        ty_of(b.func.dfg.value_type(v))
    }
}

fn ty_of(t: Type) -> Ty {
    if t == types::I64 {
        Ty::J
    } else if t == types::F32 {
        Ty::F
    } else if t == types::F64 {
        Ty::D
    } else {
        Ty::I
    }
}

fn wide(t: Ty) -> bool {
    matches!(t, Ty::J | Ty::D)
}

/// (parameters, return type) of a method descriptor; a reference of any class or array type is `Ty::A`.
fn parse_desc(desc: &str) -> Option<(Vec<Ty>, Option<Ty>)> {
    let rest = desc.strip_prefix('(')?;
    let close = rest.find(')')?;
    let mut params = Vec::new();
    let mut it = rest[..close].chars().peekable();
    while let Some(t) = next_type(&mut it)? {
        params.push(t);
    }
    let mut r = rest[close + 1..].chars().peekable();
    let ret = if r.peek() == Some(&'V') { None } else { next_type(&mut r)? };
    Some((params, ret))
}

/// The next type of a descriptor: Ok(None) at the end.
fn next_type(it: &mut std::iter::Peekable<std::str::Chars>) -> Option<Option<Ty>> {
    let c = match it.next() {
        None => return Some(None),
        Some(c) => c,
    };
    match c {
        'L' => {
            for x in it.by_ref() {
                if x == ';' {
                    return Some(Some(Ty::A));
                }
            }
            None
        }
        '[' => {
            while it.peek() == Some(&'[') {
                it.next();
            }
            if it.peek() == Some(&'L') {
                for x in it.by_ref() {
                    if x == ';' {
                        break;
                    }
                }
            } else {
                it.next();
            }
            Some(Some(Ty::A))
        }
        c => Some(Some(prim(c)?)),
    }
}

/// The type of a field descriptor.
fn field_ty(c: char) -> Option<Ty> {
    match c {
        'L' | '[' => Some(Ty::A),
        c => prim(c),
    }
}

fn prim(c: char) -> Option<Ty> {
    match c {
        'I' | 'Z' | 'B' | 'S' | 'C' => Some(Ty::I),
        'J' => Some(Ty::J),
        'F' => Some(Ty::F),
        'D' => Some(Ty::D),
        _ => None,
    }
}

#[no_mangle]
pub unsafe extern "C" fn nabu_jit_init(host: *const Host) -> c_int {
    if host.is_null() {
        return -1;
    }
    let mut flags = settings::builder();
    let _ = flags.set("use_colocated_libcalls", "false");
    let _ = flags.set("is_pic", "false");
    let _ = flags.set("opt_level", "speed");
    let isa = match cranelift_native::builder() {
        Ok(b) => match b.finish(settings::Flags::new(flags)) {
            Ok(isa) => isa,
            Err(_) => return -2,
        },
        Err(_) => return -3,
    };
    let module = JITModule::new(JITBuilder::with_isa(isa, default_libcall_names()));
    *JIT.lock().unwrap() = Some(Jit { module, host: *host });
    0
}

extern "C" fn jit_drem(a: f64, b: f64) -> f64 {
    a % b
}

/// One decoded instruction: where it is, its opcode and its operands (as far as they are needed).
#[derive(Clone, Copy, Debug)]
struct Insn {
    pc: usize,
    op: u8,
    a: i32,
    next: usize,
    sw: usize,
}

/// The targets of a tableswitch or lookupswitch (absolute pcs) and its default.
struct SwitchInfo {
    default: usize,
    cases: Vec<(i32, usize)>,
}

/// An entry of the exception table.
#[derive(Clone, Copy, Debug)]
struct Handler {
    start: usize,
    end: usize,
    pc: usize,
    catch_idx: i32,
}

fn decode(code: &[u8]) -> Option<(Vec<Insn>, Vec<SwitchInfo>)> {
    let mut out = Vec::new();
    let mut switches: Vec<SwitchInfo> = Vec::new();
    let rd4 = |q: usize| -> Option<i32> {
        Some(((*code.get(q)? as u32) << 24 | (*code.get(q + 1)? as u32) << 16 | (*code.get(q + 2)? as u32) << 8 | *code.get(q + 3)? as u32) as i32)
    };
    let mut pc = 0usize;
    let u2 = |p: usize| -> Option<i32> { Some((((*code.get(p)? as u16) << 8) | *code.get(p + 1)? as u16) as i32) };
    let s2 = |p: usize| -> Option<i32> { Some(u2(p)? as u16 as i16 as i32) };
    while pc < code.len() {
        let op = code[pc];
        let (a, len) = match op {
            0x00..=0x0f | 0x1a..=0x35 | 0x3b..=0x56 | 0x57..=0x5f | 0x60..=0x83 | 0x85..=0x98 => (0, 1),
            0xac..=0xb1 | 0xbe => (0, 1),
            0xbc => (*code.get(pc + 1)? as i32, 2),
            0xb2..=0xb5 => (u2(pc + 1)?, 3),
            0x10 => (*code.get(pc + 1)? as i8 as i32, 2),
            0x11 => (s2(pc + 1)?, 3),
            0x12 => (*code.get(pc + 1)? as i32, 2),
            0x13 | 0x14 => (u2(pc + 1)?, 3),
            0x15..=0x19 | 0x36..=0x3a => (*code.get(pc + 1)? as i32, 2),
            0x84 => ((*code.get(pc + 1)? as i32) | ((*code.get(pc + 2)? as i8 as i32) << 16), 3),
            0xc4 => {
                // wide: a load, a store or an iinc with a 16-bit index
                let wop = *code.get(pc + 1)?;
                let idx = u2(pc + 2)?;
                if wop == 0x84 {
                    let delta = s2(pc + 4)?;
                    out.push(Insn { pc, op: 0x84, a: idx | (delta << 16), next: pc + 6, sw: 0 });
                    pc += 6;
                    continue;
                } else if matches!(wop, 0x15..=0x19 | 0x36..=0x3a) {
                    out.push(Insn { pc, op: wop, a: idx, next: pc + 4, sw: 0 });
                    pc += 4;
                    continue;
                }
                return None;
            }
            0xbd => (u2(pc + 1)?, 3),
            0x99..=0xa7 | 0xc6 | 0xc7 => (pc as i32 + s2(pc + 1)?, 3),
            0xb6..=0xb8 | 0xbb | 0xc0 | 0xc1 => (u2(pc + 1)?, 3),
            0xb9 | 0xba => (u2(pc + 1)?, 5),
            0xbf | 0xc2 | 0xc3 => (0, 1),
            0xaa | 0xab => {
                let mut p = pc + 1;
                while p % 4 != 0 {
                    p += 1;
                }
                let default = (pc as i32 + rd4(p)?) as usize;
                p += 4;
                let mut cases = Vec::new();
                if op == 0xaa {
                    let lo = rd4(p)?;
                    let hi = rd4(p + 4)?;
                    p += 8;
                    if hi < lo || (hi as i64 - lo as i64) > 20000 {
                        return None;
                    }
                    for k in lo..=hi {
                        cases.push((k, (pc as i32 + rd4(p)?) as usize));
                        p += 4;
                    }
                } else {
                    let n = rd4(p)?;
                    p += 4;
                    if !(0..=20000).contains(&n) {
                        return None;
                    }
                    for _ in 0..n {
                        let key = rd4(p)?;
                        let off = rd4(p + 4)?;
                        p += 8;
                        cases.push((key, (pc as i32 + off) as usize));
                    }
                }
                switches.push(SwitchInfo { default, cases });
                (0, p - pc)
            }
            _ => {
                if std::env::var("NABU_JIT_TRACE").is_ok() {
                    eprintln!("jit: opcode 0x{:02x} at pc {} is not supported", op, pc);
                }
                return None;
            }
        };
        let sw = if op == 0xaa || op == 0xab { switches.len() - 1 } else { 0 };
        out.push(Insn { pc, op, a, next: pc + len, sw });
        pc += len;
    }
    Some((out, switches))
}

fn is_switch(op: u8) -> bool {
    matches!(op, 0xaa | 0xab)
}

fn is_branch(op: u8) -> bool {
    matches!(op, 0x99..=0xa7 | 0xc6 | 0xc7)
}

fn ends_block(op: u8) -> bool {
    matches!(op, 0xa7 | 0xac..=0xb1 | 0xbf | 0xaa | 0xab)
}

/// The types of the operand stack at the start of every block, found by running over the code once; None when the code is
/// something this compiler does not handle (a stack that differs between two ways to a block, a pop of an empty stack).
fn stack_types(
    insns: &[Insn],
    index: &BTreeMap<usize, usize>,
    leaders: &BTreeSet<usize>,
    switches: &[SwitchInfo],
    handlers: &[Handler],
    host: &Host,
    cp: *mut c_void,
) -> Option<BTreeMap<usize, Vec<Ty>>> {
    let mut entry: BTreeMap<usize, Vec<Ty>> = BTreeMap::new();
    let mut work = vec![0usize];
    entry.insert(0, Vec::new());
    for h in handlers {   // a handler starts with the exception on the stack
        entry.insert(h.pc, vec![Ty::A]);
        work.push(h.pc);
    }
    let mut done: BTreeSet<usize> = BTreeSet::new();
    let mut note = |entry: &mut BTreeMap<usize, Vec<Ty>>, work: &mut Vec<usize>, pc: usize, st: &Vec<Ty>| -> Option<()> {
        match entry.get(&pc) {
            Some(existing) if existing != st => return None,
            Some(_) => {}
            None => {
                entry.insert(pc, st.clone());
            }
        }
        work.push(pc);
        Some(())
    };
    while let Some(start) = work.pop() {
        if !done.insert(start) {
            continue;
        }
        let mut stack = entry.get(&start)?.clone();
        let mut i = *index.get(&start)?;
        loop {
            let insn = insns[i];
            apply_types(&insn, &mut stack, host, cp)?;
            if is_switch(insn.op) {
                let info = &switches[insn.sw];
                note(&mut entry, &mut work, info.default, &stack)?;
                for (_, t) in &info.cases {
                    note(&mut entry, &mut work, *t, &stack)?;
                }
                break;
            }
            if is_branch(insn.op) {
                note(&mut entry, &mut work, insn.a as usize, &stack)?;
            }
            if is_branch(insn.op) || ends_block(insn.op) {
                if insn.op != 0xa7 && is_branch(insn.op) {
                    note(&mut entry, &mut work, insn.next, &stack)?;
                }
                break;
            }
            if leaders.contains(&insn.next) {
                note(&mut entry, &mut work, insn.next, &stack)?;
                break;
            }
            i = *index.get(&insn.next)?;
        }
    }
    Some(entry)
}

/// The type and the slot (offset or address) of a field.
fn field_kind(host: &Host, cp: *mut c_void, idx: i32, is_static: bool) -> Option<(Ty, i64)> {
    let mut out = 0i64;
    let mut kind: c_char = 0;
    if (host.resolve_field)(cp, idx, is_static as c_int, &mut out, &mut kind) == 0 {
        return None;
    }
    Some((field_ty(kind as u8 as char)?, out))
}

fn pop_ty(stack: &mut Vec<Ty>) -> Option<Ty> {
    stack.pop()
}

/// The effect of one instruction on the types of the stack.
fn apply_types(insn: &Insn, st: &mut Vec<Ty>, host: &Host, cp: *mut c_void) -> Option<()> {
    use Ty::*;
    match insn.op {
        0x00 => {}
        0x01 => st.push(A),
        0x19 | 0x2a..=0x2d => st.push(A),
        0x3a | 0x4b..=0x4e => {
            pop_ty(st)?;
        }
        0x2e | 0x33..=0x35 => {
            pop_ty(st)?;
            pop_ty(st)?;
            st.push(I);
        }
        0x2f => {
            pop_ty(st)?;
            pop_ty(st)?;
            st.push(J);
        }
        0x30 => {
            pop_ty(st)?;
            pop_ty(st)?;
            st.push(F);
        }
        0x31 => {
            pop_ty(st)?;
            pop_ty(st)?;
            st.push(D);
        }
        0x32 => {
            pop_ty(st)?;
            pop_ty(st)?;
            st.push(A);
        }
        0x4f..=0x56 => {
            pop_ty(st)?;
            pop_ty(st)?;
            pop_ty(st)?;
        }
        0xbe => {
            pop_ty(st)?;
            st.push(I);
        }
        0xbc | 0xbd => {
            pop_ty(st)?;
            st.push(A);
        }
        0xb2 | 0xb4 => {
            let t = field_kind(host, cp, insn.a, insn.op == 0xb2)?.0;
            if insn.op == 0xb4 {
                pop_ty(st)?;
            }
            st.push(t);
        }
        0xb3 | 0xb5 => {
            field_kind(host, cp, insn.a, insn.op == 0xb3)?;
            pop_ty(st)?;
            if insn.op == 0xb5 {
                pop_ty(st)?;
            }
        }
        0xc6 | 0xc7 => {
            pop_ty(st)?;
        }
        0xa5 | 0xa6 => {
            pop_ty(st)?;
            pop_ty(st)?;
        }
        0x02..=0x08 | 0x10 | 0x11 => st.push(I),
        0x09 | 0x0a => st.push(J),
        0x0b..=0x0d => st.push(F),
        0x0e | 0x0f => st.push(D),
        0x12 | 0x13 => {
            let mut i = 0i32;
            let mut f = 0f32;
            if (host.cp_int)(cp, insn.a, &mut i) != 0 {
                st.push(I)
            } else if (host.cp_float)(cp, insn.a, &mut f) != 0 {
                st.push(F)
            } else if !(host.ldc_ref)(cp, insn.a).is_null() {
                st.push(A)
            } else {
                return None;
            }
        }
        0x14 => {
            let mut l = 0i64;
            let mut d = 0f64;
            if (host.cp_long)(cp, insn.a, &mut l) != 0 {
                st.push(J)
            } else if (host.cp_double)(cp, insn.a, &mut d) != 0 {
                st.push(D)
            } else {
                return None;
            }
        }
        0x15 | 0x1a..=0x1d => st.push(I),
        0x16 | 0x1e..=0x21 => st.push(J),
        0x17 | 0x22..=0x25 => st.push(F),
        0x18 | 0x26..=0x29 => st.push(D),
        0x36 | 0x3b..=0x3e | 0x37 | 0x3f..=0x42 | 0x38 | 0x43..=0x46 | 0x39 | 0x47..=0x4a => {
            pop_ty(st)?;
        }
        0x57 => {
            pop_ty(st)?;
        }
        0x58 => {
            let t = pop_ty(st)?;
            if !wide(t) {
                pop_ty(st)?;
            }
        }
        0x59 => {
            let t = *st.last()?;
            st.push(t);
        }
        0x5a => {
            let a = pop_ty(st)?;
            let b = pop_ty(st)?;
            st.push(a);
            st.push(b);
            st.push(a);
        }
        0x5b => {
            let a = pop_ty(st)?;
            let b = pop_ty(st)?;
            if wide(b) {
                st.push(a);
                st.push(b);
                st.push(a);
            } else {
                let c = pop_ty(st)?;
                st.push(a);
                st.push(c);
                st.push(b);
                st.push(a);
            }
        }
        0x5c => {
            let a = *st.last()?;
            if wide(a) {
                st.push(a);
            } else {
                let b = *st.get(st.len().checked_sub(2)?)?;
                st.push(b);
                st.push(a);
            }
        }
        0x5d => {
            let a = pop_ty(st)?;
            if wide(a) {
                let b = pop_ty(st)?;
                st.push(a);
                st.push(b);
                st.push(a);
            } else {
                let b = pop_ty(st)?;
                let c = pop_ty(st)?;
                st.push(b);
                st.push(a);
                st.push(c);
                st.push(b);
                st.push(a);
            }
        }
        0x5e => {
            let a = pop_ty(st)?;
            if wide(a) {
                let b = pop_ty(st)?;
                if wide(b) {
                    st.push(a);
                    st.push(b);
                    st.push(a);
                } else {
                    let c = pop_ty(st)?;
                    st.push(a);
                    st.push(c);
                    st.push(b);
                    st.push(a);
                }
            } else {
                let b = pop_ty(st)?;
                let c = pop_ty(st)?;
                if wide(c) {
                    st.push(b);
                    st.push(a);
                    st.push(c);
                    st.push(b);
                    st.push(a);
                } else {
                    let d = pop_ty(st)?;
                    st.push(b);
                    st.push(a);
                    st.push(d);
                    st.push(c);
                    st.push(b);
                    st.push(a);
                }
            }
        }
        0x5f => {
            let a = pop_ty(st)?;
            let b = pop_ty(st)?;
            st.push(a);
            st.push(b);
        }
        0x60..=0x73 => {
            // binary arithmetic: the result has the type of the operands
            let t = pop_ty(st)?;
            pop_ty(st)?;
            st.push(t);
        }
        0x74..=0x77 => {}
        0x78 | 0x7a | 0x7c => {
            pop_ty(st)?;
        }
        0x79 | 0x7b | 0x7d => {
            pop_ty(st)?;
        }
        0x7e..=0x83 => {
            let t = pop_ty(st)?;
            pop_ty(st)?;
            st.push(t);
        }
        0x84 => {}
        0x85 | 0x8c | 0x8f => {
            pop_ty(st)?;
            st.push(J);
        }
        0x86 | 0x89 | 0x90 => {
            pop_ty(st)?;
            st.push(F);
        }
        0x87 | 0x8a | 0x8d => {
            pop_ty(st)?;
            st.push(D);
        }
        0x88 | 0x8b | 0x8e | 0x91..=0x93 => {
            pop_ty(st)?;
            st.push(I);
        }
        0x94..=0x98 => {
            pop_ty(st)?;
            pop_ty(st)?;
            st.push(I);
        }
        0x99..=0x9e => {
            pop_ty(st)?;
        }
        0x9f..=0xa4 => {
            pop_ty(st)?;
            pop_ty(st)?;
        }
        0xa7 => {}
        0xac..=0xb0 => {
            pop_ty(st)?;
        }
        0xb1 => {}
        0xbb => {
            st.push(A);
        }
        0xbf | 0xc2 | 0xc3 | 0xaa | 0xab => {
            pop_ty(st)?;
        }
        0xc0 => {}
        0xc1 => {
            pop_ty(st)?;
            st.push(I);
        }
        0xb6..=0xba => {
            let mut method: *mut c_void = std::ptr::null_mut();
            let mut buf = [0 as c_char; 256];
            if (host.resolve_invoke)(cp, insn.a, insn.op as c_int, &mut method, buf.as_mut_ptr(), buf.len() as c_int) == 0 {
                return None;
            }
            let desc = unsafe { CStr::from_ptr(buf.as_ptr()) }.to_str().ok()?;
            let (params, ret) = parse_desc(desc)?;
            for _ in 0..params.len() {
                pop_ty(st)?;
            }
            if insn.op != 0xb8 && insn.op != 0xba {
                pop_ty(st)?;
            }
            if let Some(r) = ret {
                st.push(r);
            }
        }
        _ => return None,
    }
    Some(())
}

/// Compiles a method; returns the code (extern "C" fn(*const i64) -> i64) or NULL.
///
/// # Safety
/// `code` must point at `len` bytes, `desc` at a C string, `cp` is handed to the host callbacks.
#[no_mangle]
pub unsafe extern "C" fn nabu_jit_compile(
    code: *const u8,
    len: usize,
    desc: *const c_char,
    is_static: c_int,
    max_locals: c_int,
    cp: *mut c_void,
    self_method: *mut c_void,
    exc_count: c_int,
    exc_start: *const c_int,
    exc_end: *const c_int,
    exc_handler: *const c_int,
    exc_type: *const c_int,
) -> *mut c_void {
    if code.is_null() || desc.is_null() || len == 0 || len > 16384 {
        return std::ptr::null_mut();
    }
    let desc = match CStr::from_ptr(desc).to_str() {
        Ok(d) => d,
        Err(_) => return std::ptr::null_mut(),
    };
    let code = std::slice::from_raw_parts(code, len);
    let mut guard = match JIT.lock() {
        Ok(g) => g,
        Err(_) => return std::ptr::null_mut(),
    };
    let jit = match guard.as_mut() {
        Some(j) => j,
        None => return std::ptr::null_mut(),
    };
    let mut handlers = Vec::new();
    for i in 0..exc_count.max(0) as usize {
        handlers.push(Handler {
            start: *exc_start.add(i) as usize,
            end: *exc_end.add(i) as usize,
            pc: *exc_handler.add(i) as usize,
            catch_idx: *exc_type.add(i),
        });
    }
    match compile(jit, code, desc, is_static != 0, max_locals.max(0) as usize, cp, self_method, handlers) {
        Some(p) => p as *mut c_void,
        None => std::ptr::null_mut(),
    }
}

fn compile(jit: &mut Jit, code: &[u8], desc: &str, is_static: bool, max_locals: usize, cp: *mut c_void, self_method: *mut c_void, handlers: Vec<Handler>) -> Option<*const u8> {
    let (mut params, ret) = parse_desc(desc)?;
    if !is_static {
        params.insert(0, Ty::A);
    }
    let host = jit.host;
    let (insns, switches) = decode(code)?;
    let index: BTreeMap<usize, usize> = insns.iter().enumerate().map(|(i, x)| (x.pc, i)).collect();
    // leaders: the start, the targets of branches and what follows a branch or a return
    let mut leaders: BTreeSet<usize> = BTreeSet::new();
    leaders.insert(0);
    for insn in &insns {
        if is_branch(insn.op) {
            if !index.contains_key(&(insn.a as usize)) {
                return None;
            }
            leaders.insert(insn.a as usize);
        }
        if is_switch(insn.op) {
            let info = &switches[insn.sw];
            for t in std::iter::once(&info.default).chain(info.cases.iter().map(|c| &c.1)) {
                if !index.contains_key(t) {
                    return None;
                }
                leaders.insert(*t);
            }
        }
        if is_branch(insn.op) || ends_block(insn.op) {
            leaders.insert(insn.next);
        }
    }
    for h in &handlers {
        if !index.contains_key(&h.pc) {
            return None;
        }
        leaders.insert(h.pc);
    }
    let entry_types = stack_types(&insns, &index, &leaders, &switches, &handlers, &host, cp)?;

    let ptr = jit.module.target_config().pointer_type();
    let cc = jit.module.target_config().default_call_conv;
    let mut sig = jit.module.make_signature();
    sig.params.push(AbiParam::new(ptr));
    sig.returns.push(AbiParam::new(types::I64));
    let id = jit.module.declare_anonymous_function(&sig).ok()?;
    let mut ctx = jit.module.make_context();
    ctx.func.signature = sig;
    let mut fctx = FunctionBuilderContext::new();
    {
        let mut b = FunctionBuilder::new(&mut ctx.func, &mut fctx);
        let self_ref = jit.module.declare_func_in_func(id, b.func);
        let pending_addr = host.pending as usize as i64;
        let entry = b.create_block();
        b.append_block_params_for_function_params(entry);
        // one Cranelift block per reachable JVM block, with the stack as block parameters
        let mut blocks = BTreeMap::new();
        for (&pc, tys) in &entry_types {
            if !leaders.contains(&pc) && pc != 0 {
                continue;
            }
            let blk = b.create_block();
            for t in tys {
                b.append_block_param(blk, clty(*t));
            }
            blocks.insert(pc, blk);
        }
        let mut refs: BTreeSet<Value> = BTreeSet::new();
        let mut vars: BTreeMap<(usize, Ty), Variable> = BTreeMap::new();
        let mut var = |b: &mut FunctionBuilder, slot: usize, t: Ty| -> Variable {
            *vars.entry((slot, t)).or_insert_with(|| b.declare_var(clty(t)))
        };

        b.switch_to_block(entry);
        let args = b.block_params(entry)[0];
        let mut slot = 0usize;
        for (i, t) in params.iter().enumerate() {
            let raw = b.ins().load(types::I64, MemFlagsData::trusted(), args, (i * 8) as i32);
            let v = from_slot(&mut b, raw, *t);
            if *t == Ty::A {
                refs.insert(v);
            }
            let x = var(&mut b, slot, *t);
            b.def_var(x, v);
            slot += if wide(*t) { 2 } else { 1 };
        }
        let _ = max_locals;
        b.ins().jump(blocks[&0], &[]);

        let throw_fn = host.throw_arith as usize as i64;
        let call_fn = host.call as usize as i64;
        let drem_fn = jit_drem as usize as i64;
        let throw_exc_fn = host.throw_exc as usize as i64;
        let new_array_fn = host.new_array as usize as i64;

        let ex = ExcCtx {
            handlers,
            blocks: &blocks,
            switches: &switches,
            match_fn: host.exc_match as usize as i64,
            take_fn: host.exc_take as usize as i64,
            cp: cp as usize as i64,
            cur_pc: std::cell::Cell::new(0),
        };
        for (&start, &blk) in &blocks {
            b.switch_to_block(blk);
            let mut stack: Vec<Value> = b.block_params(blk).to_vec();
            for (v, t) in stack.iter().zip(&entry_types[&start]) {
                if *t == Ty::A {
                    refs.insert(*v);
                }
            }
            let mut i = index[&start];
            loop {
                let insn = insns[i];
                let done = emit(
                    &mut b, &insn, &mut stack, &mut var, &blocks, &host, cp, ret, throw_fn, call_fn, drem_fn, ptr, cc, self_method, self_ref, pending_addr, &mut refs, throw_exc_fn, new_array_fn, &ex,
                )?;
                if done {
                    break;
                }
                // not a block end: the next instruction may start a block
                if blocks.contains_key(&insn.next) {
                    let args: Vec<BlockArg> = stack.iter().map(|v| (*v).into()).collect();
                    b.ins().jump(blocks[&insn.next], &args);
                    break;
                }
                i = *index.get(&insn.next)?;
            }
        }
        b.seal_all_blocks();
        b.finalize(jit.module.target_config());
    }
    jit.module.define_function(id, &mut ctx).ok()?;
    jit.module.clear_context(&mut ctx);
    jit.module.finalize_definitions().ok()?;
    Some(jit.module.get_finalized_function(id))
}

fn to_slot(b: &mut FunctionBuilder, v: Value, t: Ty) -> Value {
    match t {
        Ty::I => b.ins().sextend(types::I64, v),
        Ty::J | Ty::A => v,
        Ty::F => {
            let bits = b.ins().bitcast(types::I32, MemFlagsData::new(), v);
            b.ins().uextend(types::I64, bits)
        }
        Ty::D => b.ins().bitcast(types::I64, MemFlagsData::new(), v),
    }
}

fn from_slot(b: &mut FunctionBuilder, raw: Value, t: Ty) -> Value {
    match t {
        Ty::I => b.ins().ireduce(types::I32, raw),
        Ty::J | Ty::A => raw,
        Ty::F => {
            let bits = b.ins().ireduce(types::I32, raw);
            b.ins().bitcast(types::F32, MemFlagsData::new(), bits)
        }
        Ty::D => b.ins().bitcast(types::F64, MemFlagsData::new(), raw),
    }
}

fn zero_of(b: &mut FunctionBuilder, ret: Option<Ty>) -> Value {
    let _ = ret;
    b.ins().iconst(types::I64, 0)
}

/// What leaving a method with an exception needs: the handlers that may catch it.
struct ExcCtx<'a> {
    handlers: Vec<Handler>,
    blocks: &'a BTreeMap<usize, cranelift_codegen::ir::Block>,
    switches: &'a [SwitchInfo],
    match_fn: i64,
    take_fn: i64,
    cp: i64,
    cur_pc: std::cell::Cell<usize>,
}

/// An exception is pending: go to the handler of the instruction being compiled that catches it, or leave the method.
fn exc_exit(b: &mut FunctionBuilder, ex: &ExcCtx, ptr: Type, cc: CallConv) {
    let pc = ex.cur_pc.get();
    let covering: Vec<Handler> = ex.handlers.iter().copied().filter(|h| pc >= h.start && pc < h.end).collect();
    for h in covering {
        let target = match ex.blocks.get(&h.pc) {
            Some(t) => *t,
            None => continue,
        };
        let take_blk = if h.catch_idx == 0 { None } else { Some(b.create_block()) };
        let next_blk = b.create_block();
        if let Some(tb) = take_blk {
            let mut s = Signature::new(cc);
            s.params.push(AbiParam::new(ptr));
            s.params.push(AbiParam::new(types::I32));
            s.returns.push(AbiParam::new(types::I32));
            let sigref = b.import_signature(s);
            let f = b.ins().iconst(ptr, ex.match_fn);
            let cpv = b.ins().iconst(ptr, ex.cp);
            let idx = b.ins().iconst(types::I32, h.catch_idx as i64);
            let call = b.ins().call_indirect(sigref, f, &[cpv, idx]);
            let matched = b.inst_results(call)[0];
            b.ins().brif(matched, tb, &[], next_blk, &[]);
            b.switch_to_block(tb);
        }
        let mut s = Signature::new(cc);
        s.returns.push(AbiParam::new(ptr));
        let sigref = b.import_signature(s);
        let f = b.ins().iconst(ptr, ex.take_fn);
        let call = b.ins().call_indirect(sigref, f, &[]);
        let e = b.inst_results(call)[0];
        b.ins().jump(target, &[e.into()]);
        b.switch_to_block(next_blk);
    }
    let z = b.ins().iconst(types::I64, 0);
    b.ins().return_(&[z]);
}

/// Leaves the method with an exception pending when `fail` is non-zero.
fn throw_if(b: &mut FunctionBuilder, fail: Value, kind: i64, ptr: Type, cc: CallConv, throw_exc_fn: i64, ex: &ExcCtx) {
    let fail_blk = b.create_block();
    let ok_blk = b.create_block();
    b.set_cold_block(fail_blk);
    b.ins().brif(fail, fail_blk, &[], ok_blk, &[]);
    b.switch_to_block(fail_blk);
    let mut s = Signature::new(cc);
    s.params.push(AbiParam::new(types::I32));
    let sigref = b.import_signature(s);
    let f = b.ins().iconst(ptr, throw_exc_fn);
    let k = b.ins().iconst(types::I32, kind);
    b.ins().call_indirect(sigref, f, &[k]);
    exc_exit(b, ex, ptr, cc);
    b.switch_to_block(ok_blk);
}

fn null_check(b: &mut FunctionBuilder, obj: Value, ptr: Type, cc: CallConv, throw_exc_fn: i64, ex: &ExcCtx) {
    let isnull = b.ins().icmp_imm_s(IntCC::Equal, obj, 0);
    throw_if(b, isnull, 1, ptr, cc, throw_exc_fn, ex);
}

/// The address of element `idx` of an array (null and bounds checked): the length is at byte 0, the elements from byte 8.
fn array_addr(b: &mut FunctionBuilder, arr: Value, idx: Value, size: i64, ptr: Type, cc: CallConv, throw_exc_fn: i64, ex: &ExcCtx) -> Value {
    null_check(b, arr, ptr, cc, throw_exc_fn, ex);
    let len = b.ins().load(types::I32, MemFlagsData::trusted(), arr, 0);
    let bad = b.ins().icmp(IntCC::UnsignedGreaterThanOrEqual, idx, len);
    throw_if(b, bad, 2, ptr, cc, throw_exc_fn, ex);
    let wide_idx = b.ins().uextend(types::I64, idx);
    let off = b.ins().imul_imm_s(wide_idx, size);
    let base = b.ins().iadd(arr, off);
    b.ins().iadd_imm_s(base, 8)
}

/// Emits one instruction; Some(true) when the block is finished by it.
#[allow(clippy::too_many_arguments)]
fn emit(
    b: &mut FunctionBuilder,
    insn: &Insn,
    st: &mut Vec<Value>,
    var: &mut impl FnMut(&mut FunctionBuilder, usize, Ty) -> Variable,
    blocks: &BTreeMap<usize, cranelift_codegen::ir::Block>,
    host: &Host,
    cp: *mut c_void,
    ret: Option<Ty>,
    throw_fn: i64,
    call_fn: i64,
    drem_fn: i64,
    ptr: Type,
    cc: CallConv,
    self_method: *mut c_void,
    self_ref: cranelift_codegen::ir::FuncRef,
    pending_addr: i64,
    refs: &mut BTreeSet<Value>,
    throw_exc_fn: i64,
    new_array_fn: i64,
    ex: &ExcCtx,
) -> Option<bool> {
    let op = insn.op;
    ex.cur_pc.set(insn.pc);
    match op {
        0xc2 | 0xc3 => {
            st.pop()?;   // monitorenter / monitorexit: one thread at a time
        }
        0xaa | 0xab => {
            let key = st.pop()?;
            if !st.is_empty() {
                return None;
            }
            let info = &ex.switches[insn.sw];
            let mut sw = cranelift_frontend::Switch::new();
            for (k, t) in &info.cases {
                sw.set_entry(*k as u32 as u128, *ex.blocks.get(t)?);
            }
            sw.emit(b, key, *ex.blocks.get(&info.default)?);
            return Some(true);
        }
        0x00 => {}
        0x01 => {
            let v = b.ins().iconst(types::I64, 0);
            refs.insert(v);
            st.push(v);
        }
        0x2e..=0x35 => {
            let idx = st.pop()?;
            let arr = st.pop()?;
            let addr = array_addr(b, arr, idx, [4, 8, 4, 8, 8, 1, 2, 2][(op - 0x2e) as usize], ptr, cc, throw_exc_fn, ex);
            let fl = MemFlagsData::trusted();
            let v = match op {
                0x2e => b.ins().load(types::I32, fl, addr, 0),
                0x2f => b.ins().load(types::I64, fl, addr, 0),
                0x30 => b.ins().load(types::F32, fl, addr, 0),
                0x31 => b.ins().load(types::F64, fl, addr, 0),
                0x32 => {
                    let v = b.ins().load(types::I64, fl, addr, 0);
                    refs.insert(v);
                    v
                }
                0x33 => b.ins().sload8(types::I32, fl, addr, 0),
                0x34 => b.ins().uload16(types::I32, fl, addr, 0),
                _ => b.ins().sload16(types::I32, fl, addr, 0),
            };
            st.push(v);
        }
        0x4f..=0x56 => {
            let val = st.pop()?;
            let idx = st.pop()?;
            let arr = st.pop()?;
            let addr = array_addr(b, arr, idx, [4, 8, 4, 8, 8, 1, 2, 2][(op - 0x4f) as usize], ptr, cc, throw_exc_fn, ex);
            let fl = MemFlagsData::trusted();
            match op {
                0x4f..=0x53 => {
                    b.ins().store(fl, val, addr, 0);
                }
                0x54 => {
                    b.ins().istore8(fl, val, addr, 0);
                }
                _ => {
                    b.ins().istore16(fl, val, addr, 0);
                }
            }
        }
        0xbe => {
            let arr = st.pop()?;
            null_check(b, arr, ptr, cc, throw_exc_fn, ex);
            let len = b.ins().load(types::I32, MemFlagsData::trusted(), arr, 0);
            st.push(len);
        }
        0xbc | 0xbd => {
            let count = st.pop()?;
            let mut s = Signature::new(cc);
            s.params.push(AbiParam::new(types::I32));
            s.params.push(AbiParam::new(types::I32));
            s.returns.push(AbiParam::new(ptr));
            let sigref = b.import_signature(s);
            let f = b.ins().iconst(ptr, new_array_fn);
            let at = b.ins().iconst(types::I32, if op == 0xbd { 12 } else { insn.a as i64 });
            let call = b.ins().call_indirect(sigref, f, &[at, count]);
            let r = b.inst_results(call)[0];
            refs.insert(r);
            st.push(r);
        }
        0xc1 => {
            let obj = st.pop()?;
            let mut sg = Signature::new(cc);
            sg.params.push(AbiParam::new(ptr));
            sg.params.push(AbiParam::new(types::I32));
            sg.params.push(AbiParam::new(ptr));
            sg.returns.push(AbiParam::new(types::I32));
            let sigref = b.import_signature(sg);
            let f = b.ins().iconst(ptr, host.instance_of as usize as i64);
            let cpv = b.ins().iconst(ptr, cp as usize as i64);
            let idxv = b.ins().iconst(types::I32, insn.a as i64);
            let call = b.ins().call_indirect(sigref, f, &[cpv, idxv, obj]);
            let r = b.inst_results(call)[0];
            st.push(r);
        }
        0xb2..=0xb5 => {
            let (t, slot) = field_kind(host, cp, insn.a, op == 0xb2 || op == 0xb3)?;
            let fl = MemFlagsData::trusted();
            if slot == -1 && (op == 0xb2 || op == 0xb3) {
                // a static that the interpreter handles itself (a JDK class, System.out)
                let cpv = b.ins().iconst(ptr, cp as usize as i64);
                let idxv = b.ins().iconst(types::I32, insn.a as i64);
                let mut sg = Signature::new(cc);
                sg.params.push(AbiParam::new(ptr));
                sg.params.push(AbiParam::new(types::I32));
                let status;
                let mut res_slot = None;
                if op == 0xb2 {
                    let rs = b.create_sized_stack_slot(StackSlotData::new(StackSlotKind::ExplicitSlot, 8, 3));
                    res_slot = Some(rs);
                    sg.params.push(AbiParam::new(ptr));
                    sg.returns.push(AbiParam::new(types::I32));
                    let sigref = b.import_signature(sg);
                    let f = b.ins().iconst(ptr, host.get_static as usize as i64);
                    let r = b.ins().stack_addr(ptr, rs, 0);
                    let call = b.ins().call_indirect(sigref, f, &[cpv, idxv, r]);
                    status = b.inst_results(call)[0];
                } else {
                    let v = st.pop()?;
                    let raw = to_slot(b, v, t);
                    sg.params.push(AbiParam::new(types::I64));
                    sg.returns.push(AbiParam::new(types::I32));
                    let sigref = b.import_signature(sg);
                    let f = b.ins().iconst(ptr, host.put_static as usize as i64);
                    let call = b.ins().call_indirect(sigref, f, &[cpv, idxv, raw]);
                    status = b.inst_results(call)[0];
                }
                let fail_blk = b.create_block();
                let cont_blk = b.create_block();
                b.ins().brif(status, fail_blk, &[], cont_blk, &[]);
                b.switch_to_block(fail_blk);
                exc_exit(b, ex, ptr, cc);
                b.switch_to_block(cont_blk);
                if let Some(rs) = res_slot {
                    let raw = b.ins().stack_load(ptr, types::I64, rs, 0);
                    let v = from_slot(b, raw, t);
                    if t == Ty::A {
                        refs.insert(v);
                    }
                    st.push(v);
                }
                return Some(false);
            }
            match op {
                0xb2 => {
                    let addr = b.ins().iconst(ptr, slot);
                    let raw = b.ins().load(types::I64, fl, addr, 0);
                    let v = from_slot(b, raw, t);
                    if t == Ty::A {
                        refs.insert(v);
                    }
                    st.push(v);
                }
                0xb3 => {
                    let v = st.pop()?;
                    let raw = to_slot(b, v, t);
                    let addr = b.ins().iconst(ptr, slot);
                    b.ins().store(fl, raw, addr, 0);
                }
                0xb4 => {
                    let obj = st.pop()?;
                    null_check(b, obj, ptr, cc, throw_exc_fn, ex);
                    let raw = b.ins().load(types::I64, fl, obj, slot as i32);
                    let v = from_slot(b, raw, t);
                    if t == Ty::A {
                        refs.insert(v);
                    }
                    st.push(v);
                }
                _ => {
                    let v = st.pop()?;
                    let obj = st.pop()?;
                    null_check(b, obj, ptr, cc, throw_exc_fn, ex);
                    let raw = to_slot(b, v, t);
                    b.ins().store(fl, raw, obj, slot as i32);
                }
            }
        }
        0xc6 | 0xc7 => {
            let v = st.pop()?;
            let c = b.ins().icmp_imm_s(if op == 0xc6 { IntCC::Equal } else { IntCC::NotEqual }, v, 0);
            let target = blocks[&(insn.a as usize)];
            let next = *blocks.get(&insn.next)?;
            let args: Vec<BlockArg> = st.iter().map(|v| (*v).into()).collect();
            b.ins().brif(c, target, &args, next, &args);
            return Some(true);
        }
        0xa5 | 0xa6 => {
            let r = st.pop()?;
            let l = st.pop()?;
            let c = b.ins().icmp(if op == 0xa5 { IntCC::Equal } else { IntCC::NotEqual }, l, r);
            let target = blocks[&(insn.a as usize)];
            let next = *blocks.get(&insn.next)?;
            let args: Vec<BlockArg> = st.iter().map(|v| (*v).into()).collect();
            b.ins().brif(c, target, &args, next, &args);
            return Some(true);
        }
        0x02..=0x08 => {
            let v = b.ins().iconst(types::I32, (op as i64) - 3);
            st.push(v);
        }
        0x09 | 0x0a => {
            let v = b.ins().iconst(types::I64, (op - 0x09) as i64);
            st.push(v);
        }
        0x0b..=0x0d => {
            let v = b.ins().f32const((op - 0x0b) as f32);
            st.push(v);
        }
        0x0e | 0x0f => {
            let v = b.ins().f64const((op - 0x0e) as f64);
            st.push(v);
        }
        0x10 | 0x11 => {
            let v = b.ins().iconst(types::I32, insn.a as i64);
            st.push(v);
        }
        0x12 | 0x13 => {
            let mut i = 0i32;
            let mut f = 0f32;
            let v = if (host.cp_int)(cp, insn.a, &mut i) != 0 {
                b.ins().iconst(types::I32, i as i64)
            } else if (host.cp_float)(cp, insn.a, &mut f) != 0 {
                b.ins().f32const(f)
            } else {
                let r = (host.ldc_ref)(cp, insn.a);
                if r.is_null() {
                    return None;
                }
                let v = b.ins().iconst(types::I64, r as usize as i64);
                refs.insert(v);
                v
            };
            st.push(v);
        }
        0x14 => {
            let mut l = 0i64;
            let mut d = 0f64;
            let v = if (host.cp_long)(cp, insn.a, &mut l) != 0 {
                b.ins().iconst(types::I64, l)
            } else if (host.cp_double)(cp, insn.a, &mut d) != 0 {
                b.ins().f64const(d)
            } else {
                return None;
            };
            st.push(v);
        }
        0x15..=0x19 | 0x1a..=0x2d => {
            let (t, slot) = match op {
                0x15 => (Ty::I, insn.a as usize),
                0x16 => (Ty::J, insn.a as usize),
                0x17 => (Ty::F, insn.a as usize),
                0x18 => (Ty::D, insn.a as usize),
                0x19 => (Ty::A, insn.a as usize),
                _ => {
                    let n = (op - 0x1a) as usize;
                    ([Ty::I, Ty::J, Ty::F, Ty::D, Ty::A][n / 4], n % 4)
                }
            };
            let x = var(b, slot, t);
            let v = b.use_var(x);
            if t == Ty::A {
                refs.insert(v);
            }
            st.push(v);
        }
        0x36..=0x3a | 0x3b..=0x4e => {
            let (t, slot) = match op {
                0x36 => (Ty::I, insn.a as usize),
                0x37 => (Ty::J, insn.a as usize),
                0x38 => (Ty::F, insn.a as usize),
                0x39 => (Ty::D, insn.a as usize),
                0x3a => (Ty::A, insn.a as usize),
                _ => {
                    let n = (op - 0x3b) as usize;
                    ([Ty::I, Ty::J, Ty::F, Ty::D, Ty::A][n / 4], n % 4)
                }
            };
            let v = st.pop()?;
            let x = var(b, slot, t);
            b.def_var(x, v);
        }
        0x57 => {
            st.pop()?;
        }
        0x58 => {
            let v = st.pop()?;
            if !wide(tov(b, refs, v)) {
                st.pop()?;
            }
        }
        0x59 => {
            let v = *st.last()?;
            st.push(v);
        }
        0x5a => {
            let a = st.pop()?;
            let c = st.pop()?;
            st.extend([a, c, a]);
        }
        0x5b => {
            let a = st.pop()?;
            let c = st.pop()?;
            if wide(tov(b, refs, c)) {
                st.extend([a, c, a]);
            } else {
                let d = st.pop()?;
                st.extend([a, d, c, a]);
            }
        }
        0x5c => {
            let a = *st.last()?;
            if wide(tov(b, refs, a)) {
                st.push(a);
            } else {
                let c = *st.get(st.len().checked_sub(2)?)?;
                st.extend([c, a]);
            }
        }
        0x5d => {
            let a = st.pop()?;
            if wide(tov(b, refs, a)) {
                let c = st.pop()?;
                st.extend([a, c, a]);
            } else {
                let c = st.pop()?;
                let d = st.pop()?;
                st.extend([c, a, d, c, a]);
            }
        }
        0x5e => {
            let a = st.pop()?;
            if wide(tov(b, refs, a)) {
                let c = st.pop()?;
                if wide(tov(b, refs, c)) {
                    st.extend([a, c, a]);
                } else {
                    let d = st.pop()?;
                    st.extend([a, d, c, a]);
                }
            } else {
                let c = st.pop()?;
                let d = st.pop()?;
                if wide(tov(b, refs, d)) {
                    st.extend([c, a, d, c, a]);
                } else {
                    let e = st.pop()?;
                    st.extend([c, a, e, d, c, a]);
                }
            }
        }
        0x5f => {
            let a = st.pop()?;
            let c = st.pop()?;
            st.extend([a, c]);
        }
        0x60 | 0x61 | 0x64 | 0x65 | 0x68 | 0x69 | 0x7e..=0x83 => {
            let r = st.pop()?;
            let l = st.pop()?;
            let v = match op {
                0x60 | 0x61 => b.ins().iadd(l, r),
                0x64 | 0x65 => b.ins().isub(l, r),
                0x68 | 0x69 => b.ins().imul(l, r),
                0x7e | 0x7f => b.ins().band(l, r),
                0x80 | 0x81 => b.ins().bor(l, r),
                _ => b.ins().bxor(l, r),
            };
            st.push(v);
        }
        0x62 | 0x63 | 0x66 | 0x67 | 0x6a | 0x6b | 0x6e | 0x6f => {
            let r = st.pop()?;
            let l = st.pop()?;
            let v = match op {
                0x62 | 0x63 => b.ins().fadd(l, r),
                0x66 | 0x67 => b.ins().fsub(l, r),
                0x6a | 0x6b => b.ins().fmul(l, r),
                _ => b.ins().fdiv(l, r),
            };
            st.push(v);
        }
        0x6c | 0x6d | 0x70 | 0x71 => {
            let d = st.pop()?;
            let a = st.pop()?;
            let t = b.func.dfg.value_type(a);
            let div = op == 0x6c || op == 0x6d;
            let throw_blk = b.create_block();
            let ok_blk = b.create_block();
            let neg_blk = b.create_block();
            let norm_blk = b.create_block();
            let merge = b.create_block();
            b.append_block_param(merge, t);
            let is_zero = b.ins().icmp_imm_s(IntCC::Equal, d, 0);
            b.ins().brif(is_zero, throw_blk, &[], ok_blk, &[]);
            b.switch_to_block(throw_blk);
            let sig = {
                let mut s = Signature::new(cc);
                s
            };
            let sigref = b.import_signature(sig);
            let f = b.ins().iconst(ptr, throw_fn);
            b.ins().call_indirect(sigref, f, &[]);
            exc_exit(b, ex, ptr, cc);
            b.switch_to_block(ok_blk);
            let m1 = b.ins().icmp_imm_s(IntCC::Equal, d, -1);
            b.ins().brif(m1, neg_blk, &[], norm_blk, &[]);
            b.switch_to_block(neg_blk);
            let n = if div { b.ins().ineg(a) } else { b.ins().iconst(t, 0) };
            b.ins().jump(merge, &[n.into()]);
            b.switch_to_block(norm_blk);
            let q = if div { b.ins().sdiv(a, d) } else { b.ins().srem(a, d) };
            b.ins().jump(merge, &[q.into()]);
            b.switch_to_block(merge);
            let r = b.block_params(merge)[0];
            st.push(r);
        }
        0x72 | 0x73 => {
            // frem, drem: through fmod of Rust (the semantics of the JVM)
            let r = st.pop()?;
            let l = st.pop()?;
            let float = op == 0x72;
            let (l64, r64) = if float {
                (b.ins().fpromote(types::F64, l), b.ins().fpromote(types::F64, r))
            } else {
                (l, r)
            };
            let mut s = Signature::new(cc);
            s.params.push(AbiParam::new(types::F64));
            s.params.push(AbiParam::new(types::F64));
            s.returns.push(AbiParam::new(types::F64));
            let sigref = b.import_signature(s);
            let f = b.ins().iconst(ptr, drem_fn);
            let call = b.ins().call_indirect(sigref, f, &[l64, r64]);
            let res = b.inst_results(call)[0];
            let res = if float { b.ins().fdemote(types::F32, res) } else { res };
            st.push(res);
        }
        0x74 | 0x75 => {
            let v = st.pop()?;
            let n = b.ins().ineg(v);
            st.push(n);
        }
        0x76 | 0x77 => {
            let v = st.pop()?;
            let n = b.ins().fneg(v);
            st.push(n);
        }
        0x78..=0x7d => {
            let count = st.pop()?;
            let v = st.pop()?;
            let r = match op {
                0x78 | 0x79 => b.ins().ishl(v, count),
                0x7a | 0x7b => b.ins().sshr(v, count),
                _ => b.ins().ushr(v, count),
            };
            st.push(r);
        }
        0x84 => {
            let slot = (insn.a & 0xffff) as usize;
            let delta = (insn.a >> 16) as i64;
            let x = var(b, slot, Ty::I);
            let v = b.use_var(x);
            let n = b.ins().iadd_imm_s(v, delta);
            b.def_var(x, n);
        }
        0x85 => {
            let v = st.pop()?;
            let r = b.ins().sextend(types::I64, v);
            st.push(r);
        }
        0x86 => {
            let v = st.pop()?;
            let r = b.ins().fcvt_from_sint(types::F32, v);
            st.push(r);
        }
        0x87 => {
            let v = st.pop()?;
            let r = b.ins().fcvt_from_sint(types::F64, v);
            st.push(r);
        }
        0x88 => {
            let v = st.pop()?;
            let r = b.ins().ireduce(types::I32, v);
            st.push(r);
        }
        0x89 => {
            let v = st.pop()?;
            let r = b.ins().fcvt_from_sint(types::F32, v);
            st.push(r);
        }
        0x8a => {
            let v = st.pop()?;
            let r = b.ins().fcvt_from_sint(types::F64, v);
            st.push(r);
        }
        0x8b | 0x8e => {
            let v = st.pop()?;
            let r = b.ins().fcvt_to_sint_sat(types::I32, v);
            st.push(r);
        }
        0x8c | 0x8f => {
            let v = st.pop()?;
            let r = b.ins().fcvt_to_sint_sat(types::I64, v);
            st.push(r);
        }
        0x8d => {
            let v = st.pop()?;
            let r = b.ins().fpromote(types::F64, v);
            st.push(r);
        }
        0x90 => {
            let v = st.pop()?;
            let r = b.ins().fdemote(types::F32, v);
            st.push(r);
        }
        0x91 => {
            let v = st.pop()?;
            let n = b.ins().ireduce(types::I8, v);
            let r = b.ins().sextend(types::I32, n);
            st.push(r);
        }
        0x92 => {
            let v = st.pop()?;
            let n = b.ins().ireduce(types::I16, v);
            let r = b.ins().uextend(types::I32, n);
            st.push(r);
        }
        0x93 => {
            let v = st.pop()?;
            let n = b.ins().ireduce(types::I16, v);
            let r = b.ins().sextend(types::I32, n);
            st.push(r);
        }
        0x94 => {
            let r = st.pop()?;
            let l = st.pop()?;
            let gt = b.ins().icmp(IntCC::SignedGreaterThan, l, r);
            let lt = b.ins().icmp(IntCC::SignedLessThan, l, r);
            let g = b.ins().uextend(types::I32, gt);
            let s = b.ins().uextend(types::I32, lt);
            let v = b.ins().isub(g, s);
            st.push(v);
        }
        0x95..=0x98 => {
            // fcmpl/fcmpg/dcmpl/dcmpg: a NaN gives -1 (l) or 1 (g)
            let r = st.pop()?;
            let l = st.pop()?;
            let nan_result = if op == 0x95 || op == 0x97 { -1 } else { 1 };
            let gt = b.ins().fcmp(FloatCC::GreaterThan, l, r);
            let lt = b.ins().fcmp(FloatCC::LessThan, l, r);
            let eq = b.ins().fcmp(FloatCC::Equal, l, r);
            let one = b.ins().iconst(types::I32, 1);
            let m1 = b.ins().iconst(types::I32, -1);
            let zero = b.ins().iconst(types::I32, 0);
            let nan = b.ins().iconst(types::I32, nan_result);
            let a = b.ins().select(gt, one, nan);
            let c = b.ins().select(lt, m1, a);
            let v = b.ins().select(eq, zero, c);
            st.push(v);
        }
        0x99..=0xa4 => {
            let cond = if op <= 0x9e {
                let v = st.pop()?;
                let z = b.ins().iconst(types::I32, 0);
                (v, z, op - 0x99)
            } else {
                let r = st.pop()?;
                let l = st.pop()?;
                (l, r, op - 0x9f)
            };
            let cc = [IntCC::Equal, IntCC::NotEqual, IntCC::SignedLessThan, IntCC::SignedGreaterThanOrEqual,
                      IntCC::SignedGreaterThan, IntCC::SignedLessThanOrEqual][cond.2 as usize];
            let c = b.ins().icmp(cc, cond.0, cond.1);
            let target = blocks[&(insn.a as usize)];
            let next = *blocks.get(&insn.next)?;
            let args: Vec<BlockArg> = st.iter().map(|v| (*v).into()).collect();
            b.ins().brif(c, target, &args, next, &args);
            return Some(true);
        }
        0xa7 => {
            let target = blocks[&(insn.a as usize)];
            let args: Vec<BlockArg> = st.iter().map(|v| (*v).into()).collect();
            b.ins().jump(target, &args);
            return Some(true);
        }
        0xac..=0xb0 => {
            let v = st.pop()?;
            let t = tov(b, refs, v);
            if Some(t) != ret {
                return None;
            }
            let raw = to_slot(b, v, t);
            b.ins().return_(&[raw]);
            return Some(true);
        }
        0xb1 => {
            let z = zero_of(b, ret);
            b.ins().return_(&[z]);
            return Some(true);
        }
        0xbb => {
            let mut s = Signature::new(cc);
            s.params.push(AbiParam::new(ptr));
            s.params.push(AbiParam::new(types::I32));
            s.returns.push(AbiParam::new(ptr));
            let sigref = b.import_signature(s);
            let f = b.ins().iconst(ptr, host.new_object as usize as i64);
            let cpv = b.ins().iconst(ptr, cp as usize as i64);
            let idxv = b.ins().iconst(types::I32, insn.a as i64);
            let call = b.ins().call_indirect(sigref, f, &[cpv, idxv]);
            let r = b.inst_results(call)[0];
            refs.insert(r);
            st.push(r);
        }
        0xbf => {
            let obj = st.pop()?;
            let mut s = Signature::new(cc);
            s.params.push(AbiParam::new(ptr));
            let sigref = b.import_signature(s);
            let f = b.ins().iconst(ptr, host.throw_obj as usize as i64);
            b.ins().call_indirect(sigref, f, &[obj]);
            exc_exit(b, ex, ptr, cc);
            return Some(true);
        }
        0xc0 => {}
        0xb6..=0xba => {
            let mut method: *mut c_void = std::ptr::null_mut();
            let mut buf = [0 as c_char; 256];
            let kind = (host.resolve_invoke)(cp, insn.a, op as c_int, &mut method, buf.as_mut_ptr(), buf.len() as c_int);
            if kind == 0 {
                return None;
            }
            let desc = unsafe { CStr::from_ptr(buf.as_ptr()) }.to_str().ok()?;
            let (params, cret) = parse_desc(desc)?;
            let n = params.len() + if op == 0xb8 || op == 0xba { 0 } else { 1 };
            if kind == 5 {
                // java/lang/Object.<init>: drop the receiver
                st.pop()?;
                return Some(false);
            }
            let args_slot = b.create_sized_stack_slot(StackSlotData::new(StackSlotKind::ExplicitSlot, (n.max(1) * 8) as u32, 3));
            let res_slot = b.create_sized_stack_slot(StackSlotData::new(StackSlotKind::ExplicitSlot, 8, 3));
            let mut vals: Vec<Value> = Vec::with_capacity(n);
            for _ in 0..n {
                vals.push(st.pop()?);
            }
            vals.reverse();
            for (i, v) in vals.iter().enumerate() {
                let t = tov(b, refs, *v);
                let raw = to_slot(b, *v, t);
                b.ins().stack_store(ptr, raw, args_slot, (i * 8) as i32);
            }
            let a = b.ins().stack_addr(ptr, args_slot, 0);
            if kind == 1 && method == self_method {
                // a call of this very method: straight to the machine code, then look whether it left an exception
                let call = b.ins().call(self_ref, &[a]);
                let raw = b.inst_results(call)[0];
                let pa = b.ins().iconst(ptr, pending_addr);
                let pend = b.ins().load(ptr, MemFlagsData::trusted(), pa, 0);
                let fail_blk = b.create_block();
                let cont_blk = b.create_block();
                b.ins().brif(pend, fail_blk, &[], cont_blk, &[]);
                b.switch_to_block(fail_blk);
                exc_exit(b, ex, ptr, cc);
                b.switch_to_block(cont_blk);
                if let Some(rt) = cret {
                    let v = from_slot(b, raw, rt);
                    if rt == Ty::A {
                        refs.insert(v);
                    }
                    st.push(v);
                }
                return Some(false);
            }
            let count = b.ins().iconst(types::I32, n as i64);
            let r = b.ins().stack_addr(ptr, res_slot, 0);
            let call = if kind == 3 || kind == 6 {
                let cpv = b.ins().iconst(ptr, cp as usize as i64);
                let idxv = b.ins().iconst(types::I32, insn.a as i64);
                let mut s = Signature::new(cc);
                s.params.push(AbiParam::new(ptr));
                s.params.push(AbiParam::new(types::I32));
                s.params.push(AbiParam::new(ptr));
                s.params.push(AbiParam::new(types::I32));
                s.params.push(AbiParam::new(ptr));
                s.returns.push(AbiParam::new(types::I32));
                let sigref = b.import_signature(s);
                let target = if kind == 3 { host.call_virtual as usize } else { host.call_interface as usize };
                let f = b.ins().iconst(ptr, target as i64);
                b.ins().call_indirect(sigref, f, &[cpv, idxv, a, count, r])
            } else if kind == 4 {
                let cpv = b.ins().iconst(ptr, cp as usize as i64);
                let idxv = b.ins().iconst(types::I32, insn.a as i64);
                let opv = b.ins().iconst(types::I32, op as i64);
                let mut s = Signature::new(cc);
                s.params.push(AbiParam::new(ptr));
                s.params.push(AbiParam::new(types::I32));
                s.params.push(AbiParam::new(types::I32));
                s.params.push(AbiParam::new(ptr));
                s.params.push(AbiParam::new(types::I32));
                s.params.push(AbiParam::new(ptr));
                s.returns.push(AbiParam::new(types::I32));
                let sigref = b.import_signature(s);
                let f = b.ins().iconst(ptr, host.call_synth as usize as i64);
                b.ins().call_indirect(sigref, f, &[cpv, idxv, opv, a, count, r])
            } else {
                let m = b.ins().iconst(ptr, method as usize as i64);
                let mut s = Signature::new(cc);
                s.params.push(AbiParam::new(ptr));
                s.params.push(AbiParam::new(ptr));
                s.params.push(AbiParam::new(types::I32));
                s.params.push(AbiParam::new(ptr));
                s.returns.push(AbiParam::new(types::I32));
                let sigref = b.import_signature(s);
                let f = b.ins().iconst(ptr, call_fn);
                b.ins().call_indirect(sigref, f, &[m, a, count, r])
            };
            let status = b.inst_results(call)[0];
            let fail_blk = b.create_block();
            let cont_blk = b.create_block();
            b.ins().brif(status, fail_blk, &[], cont_blk, &[]);
            b.switch_to_block(fail_blk);
            exc_exit(b, ex, ptr, cc);
            b.switch_to_block(cont_blk);
            if let Some(rt) = cret {
                let raw = b.ins().stack_load(ptr, types::I64, res_slot, 0);
                let v = from_slot(b, raw, rt);
                if rt == Ty::A {
                    refs.insert(v);
                }
                st.push(v);
            }
        }
        _ => return None,
    }
    Some(false)
}
