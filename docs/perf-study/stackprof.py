"""Sampling profiler with real stacks, from outside the process (Windows x64).

Every ~1 ms each thread of the process is suspended just long enough to copy its registers and its stack; the stack is
then unwound with the x64 unwind data (.pdata/.xdata) of the module of each frame, read from the module files on disk,
the way RtlVirtualUnwind does it. Names: the exe's global symbols, the static functions of the runtime objects
(llvm-objdump -t on OBJDIR), and the exports of the DLLs.

  stackprof.py run EXE CWD SECONDS OBJDIR [--callers name,name] [--depth N] [--port P] [path ...]
      start EXE (an HTTP server on 127.0.0.1:P, default 8080), warm it up with 1500 GETs of the paths (default /),
      then sample only while a second process sends the same GETs one after another
  stackprof.py report SAMPLES.json EXE OBJDIR [--callers ...] [--depth N]
      the same report again from the saved samples
"""
import bisect, collections, tempfile, ctypes, ctypes.wintypes as w, http.client, json, mmap, os, re, struct, subprocess, sys, time

LLVM = r'C:\Program Files\LLVM\bin'
PREFERRED = 0x140000000
PORT = 8080
MAX_STACK = 512 * 1024
MAX_FRAMES = 400
k32 = ctypes.WinDLL('kernel32', use_last_error=True)
ntdll = ctypes.WinDLL('ntdll')
psapi = ctypes.WinDLL('psapi')

k32.CreateToolhelp32Snapshot.restype = w.HANDLE
k32.OpenThread.restype = w.HANDLE
k32.OpenProcess.restype = w.HANDLE
k32.SuspendThread.argtypes = [w.HANDLE]
k32.ResumeThread.argtypes = [w.HANDLE]
k32.GetThreadContext.argtypes = [w.HANDLE, ctypes.c_void_p]
k32.CloseHandle.argtypes = [w.HANDLE]
k32.ReadProcessMemory.argtypes = [w.HANDLE, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_size_t, ctypes.POINTER(ctypes.c_size_t)]
ntdll.NtQueryInformationThread.argtypes = [w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.ULONG, ctypes.POINTER(w.ULONG)]

WAITS = re.compile(r'!(Nt|Zw)(Wait|DelayExecution|RemoveIoCompletion|SignalAndWait|ReplyWaitReceivePort|AlpcSendWaitReceivePort)')


class THREADENTRY32(ctypes.Structure):
    _fields_ = [('dwSize', w.DWORD), ('cntUsage', w.DWORD), ('th32ThreadID', w.DWORD), ('th32OwnerProcessID', w.DWORD),
                ('tpBasePri', w.LONG), ('tpDeltaPri', w.LONG), ('dwFlags', w.DWORD)]


class MODULEINFO(ctypes.Structure):
    _fields_ = [('lpBaseOfDll', ctypes.c_void_p), ('SizeOfImage', w.DWORD), ('EntryPoint', ctypes.c_void_p)]


def threads(pid):
    snap = k32.CreateToolhelp32Snapshot(4, 0)
    te = THREADENTRY32(); te.dwSize = ctypes.sizeof(te)
    out = []
    ok = k32.Thread32First(snap, ctypes.byref(te))
    while ok:
        if te.th32OwnerProcessID == pid:
            out.append(te.th32ThreadID)
        ok = k32.Thread32Next(snap, ctypes.byref(te))
    k32.CloseHandle(snap)
    return out


def read(proc, addr, n):
    buf = ctypes.create_string_buffer(n)
    got = ctypes.c_size_t()
    k32.ReadProcessMemory(proc, ctypes.c_void_p(addr), buf, n, ctypes.byref(got))
    return buf.raw[:got.value]


def modules(proc):
    arr = (ctypes.c_void_p * 1024)()
    need = w.DWORD()
    psapi.EnumProcessModulesEx(w.HANDLE(proc), arr, ctypes.sizeof(arr), ctypes.byref(need), 3)
    out = []
    name = ctypes.create_unicode_buffer(1024)
    for i in range(need.value // ctypes.sizeof(ctypes.c_void_p)):
        mi = MODULEINFO()
        psapi.GetModuleInformation(w.HANDLE(proc), ctypes.c_void_p(arr[i]), ctypes.byref(mi), ctypes.sizeof(mi))
        psapi.GetModuleFileNameExW(w.HANDLE(proc), ctypes.c_void_p(arr[i]), name, 1024)
        out.append((arr[i], mi.SizeOfImage, name.value))
    return out


def stack_base(h):
    info = (ctypes.c_byte * 48)()
    if ntdll.NtQueryInformationThread(h, 0, info, 48, None) != 0:
        return None
    return struct.unpack_from('<Q', bytes(info), 8)[0]   # TebBaseAddress


# ---------------------------------------------------------------- PE files: unwind data and exports

class PE:
    def __init__(self, path, base, size):
        self.path, self.base, self.size = path, base, size
        self.name = os.path.basename(path)
        f = open(path, 'rb')
        self.data = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
        d = self.data
        pe = struct.unpack_from('<I', d, 0x3C)[0]
        nsec = struct.unpack_from('<H', d, pe + 6)[0]
        opt = pe + 24
        optsize = struct.unpack_from('<H', d, pe + 20)[0]
        self.preferred = struct.unpack_from('<Q', d, opt + 24)[0]
        dirs = opt + 112
        self.sections = []
        for i in range(nsec):
            s = opt + optsize + i * 40
            vsize, va, rawsize, rawptr = struct.unpack_from('<IIII', d, s + 8)
            self.sections.append((va, max(vsize, rawsize), rawptr))
        exc_rva, exc_size = struct.unpack_from('<II', d, dirs + 3 * 8)
        n = exc_size // 12
        off = self.rva_to_off(exc_rva) if exc_rva else None
        self.fn_begin, self.fn_end, self.fn_unwind = [], [], []
        if off is not None:
            raw = struct.unpack_from('<%dI' % (3 * n), d, off)
            self.fn_begin = list(raw[0::3]); self.fn_end = list(raw[1::3]); self.fn_unwind = list(raw[2::3])
        self.exports = None
        self.exp_dir = struct.unpack_from('<II', d, dirs)

    def rva_to_off(self, rva):
        for va, size, raw in self.sections:
            if va <= rva < va + size:
                return raw + rva - va
        return None

    def u8(self, rva): return self.data[self.rva_to_off(rva)]

    def bytes_at(self, rva, n):
        off = self.rva_to_off(rva)
        return b'' if off is None else self.data[off:off + n]

    def function(self, rva):
        i = bisect.bisect_right(self.fn_begin, rva) - 1
        if i >= 0 and rva < self.fn_end[i]:
            return self.fn_begin[i], self.fn_end[i], self.fn_unwind[i]
        return None

    def export_name(self, rva):
        if self.exports is None:
            self.exports = ([], [])
            erva, esize = self.exp_dir
            off = self.rva_to_off(erva) if erva else None
            if off is not None:
                d = self.data
                nfun, nnames, afun, anames, aords = struct.unpack_from('<IIIII', d, off + 20)
                funs = struct.unpack_from('<%dI' % nfun, d, self.rva_to_off(afun)) if nfun else ()
                names = struct.unpack_from('<%dI' % nnames, d, self.rva_to_off(anames)) if nnames else ()
                ords = struct.unpack_from('<%dH' % nnames, d, self.rva_to_off(aords)) if nnames else ()
                pairs = []
                for nrva, o in zip(names, ords):
                    no = self.rva_to_off(nrva)
                    nm = d[no:d.find(b'\0', no)].decode('ascii', 'replace')
                    if funs[o] and not (erva <= funs[o] < erva + esize):
                        pairs.append((funs[o], nm))
                pairs.sort()
                self.exports = ([a for a, _ in pairs], [n for _, n in pairs])
        i = bisect.bisect_right(self.exports[0], rva) - 1
        return self.exports[1][i] if i >= 0 else '?'


class Unwinder:
    def __init__(self, mods):
        self.mods = sorted(mods, key=lambda m: m.base)
        self.bases = [m.base for m in self.mods]
        self.cache = {}

    def module(self, addr):
        i = bisect.bisect_right(self.bases, addr) - 1
        if i >= 0 and addr < self.mods[i].base + self.mods[i].size:
            return self.mods[i]
        return None

    def unwind_info(self, pe, rva):
        key = (pe.name, rva)
        ui = self.cache.get(key)
        if ui is None:
            hdr = pe.bytes_at(rva, 4)
            flags, prolog, count, fr = hdr[0] >> 3, hdr[1], hdr[2], hdr[3]
            codes = pe.bytes_at(rva + 4, count * 2)
            chained = None
            if flags & 4:
                c = rva + 4 + ((count + 1) & ~1) * 2
                chained = struct.unpack('<III', pe.bytes_at(c, 12))
            ui = (prolog, fr & 15, fr >> 4, [(codes[i], codes[i + 1] & 15, codes[i + 1] >> 4) for i in range(0, len(codes), 2)],
                  struct.unpack('<%dH' % count, codes) if count else (), chained)
            self.cache[key] = ui
        return ui

    def frames(self, rip, regs, stack, stack_lo):
        """Return addresses of the frames above rip: [rip, caller, caller's caller, ...]."""
        out = [rip]

        def q(addr):
            o = addr - stack_lo
            if 0 <= o and o + 8 <= len(stack):
                return struct.unpack_from('<Q', stack, o)[0]
            raise IndexError

        try:
            for _ in range(MAX_FRAMES):
                pe = self.module(rip)
                if pe is None: break
                rva = rip - pe.base
                fn = pe.function(rva)
                if fn is None:                                  # a leaf without unwind data
                    rip = q(regs[4]); regs[4] += 8
                else:
                    if not self.epilog(pe, fn, rva, regs, q):
                        self.apply(pe, fn[2], rva - fn[0], regs, q, True)
                    if regs.pop('machframe', False):
                        rip = regs.pop('rip')
                    else:
                        rip = q(regs[4]); regs[4] += 8
                if rip == 0: break
                out.append(rip)
        except (IndexError, struct.error):
            pass
        return out

    def apply(self, pe, unwind_rva, offset, regs, q, first):
        prolog, freg, foff, codes, raw, chained = self.unwind_info(pe, unwind_rva)
        i = 0
        while i < len(codes):
            at, op, info = codes[i]
            n = {0: 1, 1: 2 if info == 0 else 3, 2: 1, 3: 1, 4: 2, 5: 3, 6: 2, 7: 3, 8: 2, 9: 3, 10: 1}.get(op, 1)
            if first and offset < prolog and at > offset:     # this part of the prologue has not run yet
                i += n; continue
            if op == 0:
                regs[info] = q(regs[4]); regs[4] += 8
            elif op == 1:
                regs[4] += raw[i + 1] * 8 if info == 0 else raw[i + 1] | raw[i + 2] << 16
            elif op == 2:
                regs[4] += info * 8 + 8
            elif op == 3:
                regs[4] = regs[freg] - foff * 16
            elif op == 4:
                regs[info] = q(regs[4] + raw[i + 1] * 8)
            elif op == 5:
                regs[info] = q(regs[4] + (raw[i + 1] | raw[i + 2] << 16))
            elif op == 10:
                if info: regs[4] += 8
                regs['rip'] = q(regs[4]); regs[4] = q(regs[4] + 24); regs['machframe'] = True
                return
            i += n
        if chained:
            self.apply(pe, chained[2], 0, regs, q, False)

    @staticmethod
    def epilog(pe, fn, rva, regs, q):
        """If rip is in an epilog (add/lea rsp, pops, then ret or a jump out of the function), run it."""
        code = pe.bytes_at(rva, 64)
        i = 0
        steps = []
        if code[i:i + 3] == b'\x48\x83\xc4':
            steps.append(('add', code[i + 3])); i += 4
        elif code[i:i + 3] == b'\x48\x81\xc4':
            steps.append(('add', struct.unpack_from('<I', code, i + 3)[0])); i += 7
        elif code[i:i + 2] == b'\x48\x8d' and (code[i + 2] & 0xC0) in (0x40, 0x80) and (code[i + 2] >> 3 & 7) == 4:
            mod, rm = code[i + 2] >> 6, code[i + 2] & 7
            disp = struct.unpack_from('<b', code, i + 3)[0] if mod == 1 else struct.unpack_from('<i', code, i + 3)[0]
            steps.append(('lea', rm, disp)); i += 4 if mod == 1 else 7
        while True:
            b = code[i] if i < len(code) else 0
            if 0x58 <= b <= 0x5f:
                steps.append(('pop', b - 0x58)); i += 1
            elif b == 0x41 and i + 1 < len(code) and 0x58 <= code[i + 1] <= 0x5f:
                steps.append(('pop', code[i + 1] - 0x58 + 8)); i += 2
            else:
                break
        b = code[i] if i < len(code) else 0
        if b in (0xC3, 0xC2):
            pass
        elif b == 0xE9:
            target = rva + i + 5 + struct.unpack_from('<i', code, i + 1)[0]
            if fn[0] <= target < fn[1]: return False
        elif b == 0xFF and i + 1 < len(code) and code[i + 1] == 0x25:
            pass
        elif code[i:i + 3] == b'\x48\xff\x25' or (b == 0x48 and i + 2 < len(code) and code[i + 1] == 0xFF and (code[i + 2] >> 3 & 7) == 4):
            pass
        else:
            return False
        for s in steps:
            if s[0] == 'add': regs[4] += s[1]
            elif s[0] == 'lea': regs[4] = regs[s[1]] + s[2]
            else: regs[s[1]] = q(regs[4]); regs[4] += 8
        return True


# ---------------------------------------------------------------- sampling

def sample(pid, proc, seconds, unwinder, exe_base):
    wait_cache = {}
    cur = w.ULONG()
    ntdll.NtSetTimerResolution(10000, True, ctypes.byref(cur))
    raw = ctypes.create_string_buffer(0x4D0 + 16)
    ctx = (ctypes.addressof(raw) + 15) & ~15
    stackbuf = ctypes.create_string_buffer(MAX_STACK)
    got = ctypes.c_size_t()
    samples = []
    handles = {}
    end = time.time() + seconds
    last = 0
    while time.time() < end:
        now = time.time()
        if now - last > 0.2:
            for tid in threads(pid):
                if tid not in handles:
                    h = k32.OpenThread(0x0002 | 0x0008 | 0x0040, False, tid)
                    if h:
                        teb = stack_base(h)
                        top = struct.unpack('<Q', read(proc, teb + 8, 8))[0] if teb else 0
                        handles[tid] = (h, top)
            last = now
        for tid, (h, top) in handles.items():
            if k32.SuspendThread(h) == 0xFFFFFFFF: continue
            ctypes.c_uint32.from_address(ctx + 0x30).value = 0x100003      # CONTEXT_CONTROL | CONTEXT_INTEGER
            ok = k32.GetThreadContext(h, ctx)
            stack = b''
            if ok:
                rsp = ctypes.c_uint64.from_address(ctx + 0x98).value
                n = min(MAX_STACK, top - rsp) if top > rsp else 64 * 1024
                if k32.ReadProcessMemory(proc, ctypes.c_void_p(rsp), stackbuf, n, ctypes.byref(got)):
                    stack = stackbuf.raw[:got.value]
            k32.ResumeThread(h)
            if not ok: continue
            regs = {r: ctypes.c_uint64.from_address(ctx + 0x78 + 8 * r).value for r in range(16)}
            rip = ctypes.c_uint64.from_address(ctx + 0xF8).value
            waiting = wait_cache.get(rip)
            if waiting is None:
                pe = unwinder.module(rip)
                waiting = wait_cache[rip] = bool(pe is not None and pe.base != exe_base
                                                 and WAITS.search('!' + pe.export_name(rip - pe.base)))
            samples.append([rip] if waiting else unwinder.frames(rip, regs, stack, regs[4]))
        k32.Sleep(1)
    return samples


# ---------------------------------------------------------------- names and report

def exe_symbols(exe, objdir):
    nm = subprocess.run([os.path.join(LLVM, 'llvm-nm'), '-n', '--defined-only', exe], capture_output=True, text=True).stdout
    glob = {}
    syms = []
    for line in nm.splitlines():
        parts = line.split(' ', 2)
        if len(parts) == 3 and parts[1] in 'Tt':
            a = int(parts[0], 16); syms.append((a, parts[2])); glob[parts[2]] = a
    for f in sorted(os.listdir(objdir)) if objdir else []:
        if not f.endswith('.o'): continue
        dump = subprocess.run([os.path.join(LLVM, 'llvm-objdump'), '-t', os.path.join(objdir, f)], capture_output=True, text=True).stdout
        local, base = [], None
        for line in dump.splitlines():
            m = re.match(r'\[\s*\d+\]\(sec\s+1\)\(fl 0x00\)\(ty\s+20\)\(scl\s+(\d+)\) \(nx \d\) 0x([0-9a-f]+) (\S+)$', line)
            if not m: continue
            off, name = int(m.group(2), 16), m.group(3)
            local.append((off, name))
            if base is None and m.group(1) == '2' and name in glob:
                base = glob[name] - off
        if base is not None:
            syms += [(base + off, name + ('' if name in glob else ' [static]')) for off, name in local]
    syms.sort()
    return [a for a, _ in syms], [n for _, n in syms]


class Namer:
    def __init__(self, exe, objdir, mods):
        self.addrs, self.names = exe_symbols(exe, objdir)
        self.mods = mods          # [(base, size, path)] as sampled
        self.exe_base = next(b for b, s, p in mods if os.path.normcase(p) == os.path.normcase(os.path.abspath(exe)))
        self.pes = {}
        self.cache = {}

    def __call__(self, a):
        r = self.cache.get(a)
        if r is not None: return r
        r = '?'
        for base, size, path in self.mods:
            if base <= a < base + size:
                if base == self.exe_base:
                    p = a - base + PREFERRED
                    i = bisect.bisect_right(self.addrs, p) - 1
                    r = self.names[i] if i >= 0 else '?'
                else:
                    pe = self.pes.get(path)
                    if pe is None:
                        pe = self.pes[path] = PE(path, base, size)
                    r = '%s!%s' % (pe.name, pe.export_name(a - base))
                break
        self.cache[a] = r
        return r


def category(k):
    if '!' in k: return 'system'
    if '.clinit.once' in k: return 'clinit checks'
    if k.startswith('GC'): return 'gc'
    if re.match(r'(pthread|__pth|_pthread|__emutls|nabu_monitor|nabu_gil|jcl_thread_of)', k): return 'locks/tls'
    if '.' not in k or k.endswith('[static]'): return 'runtime C'
    if k.startswith(('java.', 'jdk.', 'sun.')): return 'jdk code'
    return 'app/framework code'


def report(stacks, namer, callers_of, depth):
    named = [[namer(a) for a in s] for s in stacks]
    running = [s for s in named if s and not WAITS.search(s[0])]
    starts = sum(1 for s in running if any(x.endswith(('!RtlUserThreadStart', '!BaseThreadInitThunk')) for x in s[-3:]))
    print('samples %d, waiting %d, running %d; stacks that reach the thread start: %.0f%%, median depth %d'
          % (len(named), len(named) - len(running), len(running), 100.0 * starts / max(1, len(running)),
             sorted(len(s) for s in running)[len(running) // 2] if running else 0))
    tot = len(running)
    cats = collections.Counter(category(s[0]) for s in running)
    print('\nself time by category')
    for k, v in cats.most_common(): print('  %5.1f%% %s' % (100.0 * v / tot, k))
    self_ = collections.Counter(s[0] for s in running)
    incl = collections.Counter()
    for s in running:
        for f in set(s): incl[f] += 1
    print('\nself')
    for k, v in self_.most_common(40): print('  %5d %5.1f%% %s' % (v, 100.0 * v / tot, k))
    print('\ninclusive (the function anywhere on the stack)')
    for k, v in incl.most_common(60): print('  %5d %5.1f%% %s' % (v, 100.0 * v / tot, k))
    for target in callers_of:
        chains = collections.Counter()
        n = 0
        for s in running:
            if target in s:
                i = s.index(target)
                chains[' <- '.join(s[i + 1:i + 1 + depth]) or '(top)'] += 1
                n += 1
        print('\ncallers of %s (%d samples, %.1f%%, inclusive)' % (target, n, 100.0 * n / tot))
        for k, v in chains.most_common(20): print('  %5d %s' % (v, k))


LOADER = r'''
import http.client, sys, time
end = time.time() + float(sys.argv[1])
c = http.client.HTTPConnection('127.0.0.1', int(sys.argv[2]), timeout=5); paths = sys.argv[3:]; n = 0
while time.time() < end:
    c.request('GET', paths[n % len(paths)]); r = c.getresponse(); r.read(); n += 1
print('requests', n, flush=True)
'''


def options(args):
    callers, depth, rest = [], 4, []
    global PORT
    i = 0
    while i < len(args):
        if args[i] == '--callers': callers = args[i + 1].split(','); i += 2
        elif args[i] == '--port': PORT = int(args[i + 1]); i += 2
        elif args[i] == '--depth': depth = int(args[i + 1]); i += 2
        else: rest.append(args[i]); i += 1
    return callers, depth, rest


def main():
    if sys.argv[1] == 'report':
        data = json.load(open(sys.argv[2]))
        callers, depth, _ = options(sys.argv[5:])
        report(data['stacks'], Namer(sys.argv[3], sys.argv[4], [tuple(m) for m in data['modules']]), callers, depth)
        return
    exe, cwd, seconds, objdir = os.path.abspath(sys.argv[2]), sys.argv[3], float(sys.argv[4]), sys.argv[5]
    callers, depth, paths = options(sys.argv[6:])
    paths = paths or ['/']
    p = subprocess.Popen([exe], cwd=cwd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        t0 = time.time()
        while True:
            try:
                c = http.client.HTTPConnection('127.0.0.1', PORT, timeout=5)
                c.request('GET', paths[0]); r = c.getresponse(); r.read()
                if r.status == 200: break
            except Exception:
                if time.time() - t0 > 120 or p.poll() is not None: raise
                time.sleep(0.2)
        for i in range(1500):
            c.request('GET', paths[i % len(paths)]); r = c.getresponse(); r.read()
        proc = k32.OpenProcess(0x0410, False, p.pid)
        mods = modules(proc)
        unwinder = Unwinder([PE(path, base, size) for base, size, path in mods])
        loader = subprocess.Popen([sys.executable, '-c', LOADER, str(seconds + 1), str(PORT)] + paths)
        t = time.time()
        exe_base = next(b for b, s, path in mods if os.path.normcase(path) == os.path.normcase(exe))
        stacks = sample(p.pid, proc, seconds, unwinder, exe_base)
        print('sampled for %.1f s' % (time.time() - t))
        loader.wait()
    finally:
        p.kill(); p.wait()
    out = os.path.join(tempfile.gettempdir(), 'stackprof_samples.json')
    json.dump({'modules': mods, 'stacks': stacks}, open(out, 'w'))
    print('saved', out)
    report(stacks, Namer(exe, objdir, mods), callers, depth)


if __name__ == '__main__':
    main()
