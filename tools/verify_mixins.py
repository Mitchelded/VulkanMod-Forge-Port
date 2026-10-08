#!/usr/bin/env python3
"""Offline Mixin target verifier.

Checks every mixin in src/main/java/net/vulkanmod/mixin against the *real* bytecode of the
Forge-patched Minecraft 1.20.1 jar in official (Mojang) names, which ForgeGradle produces at
build/fg_cache/net/minecraftforge/forge/<ver>_mapped_official_<mc>/*.jar.

What is verified (the things that make Mixin crash at class-load time with defaultRequire=1):
  * the @Mixin target class exists
  * every @Overwrite / @Shadow method and @Shadow field exists (name + descriptor where derivable)
  * every @Inject / @Redirect / @ModifyArg / @ModifyVariable / @ModifyConstant target method exists
  * every @At(value = "INVOKE" | "FIELD" | "NEW", target = ...) is actually present in the target
    method's bytecode, and ordinal (if given) is in range

It is a static approximation of Mixin's own checks; `remap=false` targets on non-Minecraft classes
(LWJGL, Forge) are verified the same way because they live in the same jar or are skipped if absent.

usage: python -I tools/verify_mixins.py <mapped-forge-jar> [src-root]
exit code 0 = all good, 1 = problems found
"""
import os
import re
import struct
import sys
import zipfile

# ----------------------------------------------------------------------------------------------
# Class file parsing


def _u2(b, o):
    return struct.unpack('>H', b[o:o + 2])[0]


def _u4(b, o):
    return struct.unpack('>I', b[o:o + 4])[0]


class ClassInfo:
    def __init__(self, data):
        self.methods = {}   # (name, desc) -> list of instructions (kind, owner, name, desc)
        self.fields = {}    # name -> desc
        self.super = None
        self.interfaces = []
        self._parse(data)

    def _parse(self, b):
        n = _u2(b, 8)
        pool = [None] * n
        i, o = 1, 10
        while i < n:
            t = b[o]
            if t == 1:
                ln = _u2(b, o + 1)
                pool[i] = b[o + 3:o + 3 + ln].decode('utf-8', 'replace')
                o += 3 + ln
            elif t in (3, 4):
                o += 5
            elif t in (5, 6):
                o += 9
                i += 1
            elif t == 7:
                pool[i] = ('C', _u2(b, o + 1)); o += 3
            elif t in (8, 16, 19, 20):
                pool[i] = ('S', _u2(b, o + 1)); o += 3
            elif t in (9, 10, 11):
                pool[i] = ('R', _u2(b, o + 1), _u2(b, o + 3)); o += 5
            elif t == 12:
                pool[i] = ('N', _u2(b, o + 1), _u2(b, o + 3)); o += 5
            elif t == 15:
                o += 4
            elif t in (17, 18):
                pool[i] = ('D', _u2(b, o + 1), _u2(b, o + 3)); o += 5
            else:
                raise ValueError(t)
            i += 1
        self.pool = pool

        def cls(idx):
            return pool[pool[idx][1]]

        def ref(idx):
            e = pool[idx]
            nat = pool[e[2]]
            return cls(e[1]), pool[nat[1]], pool[nat[2]]

        o += 2  # access
        o += 2  # this
        sup = _u2(b, o); o += 2
        self.super = cls(sup) if sup else None
        ic = _u2(b, o); o += 2
        for _ in range(ic):
            self.interfaces.append(cls(_u2(b, o))); o += 2
        fc = _u2(b, o); o += 2
        for _ in range(fc):
            name, desc = pool[_u2(b, o + 2)], pool[_u2(b, o + 4)]
            ac = _u2(b, o + 6); o += 8
            for _ in range(ac):
                o += 6 + _u4(b, o + 2)
            self.fields[name] = desc
        mc = _u2(b, o); o += 2
        for _ in range(mc):
            name, desc = pool[_u2(b, o + 2)], pool[_u2(b, o + 4)]
            ac = _u2(b, o + 6); o += 8
            insns = []
            for _ in range(ac):
                an = pool[_u2(b, o)]
                alen = _u4(b, o + 2)
                if an == 'Code':
                    clen = _u4(b, o + 10)
                    code = b[o + 14:o + 14 + clen]
                    insns = self._scan(code, ref, cls)
                o += 6 + alen
            self.methods[(name, desc)] = insns

    def _scan(self, code, ref, cls):
        out = []
        pc = 0
        L = len(code)
        # operand sizes for opcodes that are not special-cased
        size = {}
        for op in list(range(0x00, 0x10)) + list(range(0x1a, 0x36)) + list(range(0x3b, 0x84)) + \
                list(range(0x85, 0x99)) + list(range(0xac, 0xb2)) + [0xbe, 0xbf, 0xc2, 0xc3]:
            size[op] = 0
        for op in (0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3a, 0xa9, 0xbc):
            size[op] = 1
        for op in [0x11, 0x13, 0x14, 0x84] + list(range(0x99, 0xa9)) + [0xb2, 0xb3, 0xb4, 0xb5, 0xb6,
                                                                         0xb7, 0xb8, 0xbb, 0xbd, 0xc0,
                                                                         0xc1, 0xc6, 0xc7]:
            size[op] = 2
        size[0xc8] = 4
        size[0xc9] = 4
        size[0xb9] = 4
        size[0xba] = 4
        size[0xc5] = 3
        while pc < L:
            op = code[pc]
            if op in (0xb2, 0xb3, 0xb4, 0xb5):
                out.append(('FIELD',) + ref(_u2(code, pc + 1)) + (op,))
            elif op in (0xb6, 0xb7, 0xb8, 0xb9):
                out.append(('INVOKE',) + ref(_u2(code, pc + 1)) + (op,))
            elif op == 0xbb:
                out.append(('NEW', cls(_u2(code, pc + 1)), None, None, op))
            elif op in (0x12, 0x13, 0x14):
                out.append(('CONST', None, None, None, op))
            if op == 0xaa:  # tableswitch
                p = pc + 1
                p += (4 - p % 4) % 4
                lo, hi = struct.unpack('>ii', code[p + 4:p + 12])
                pc = p + 12 + 4 * (hi - lo + 1)
                continue
            if op == 0xab:  # lookupswitch
                p = pc + 1
                p += (4 - p % 4) % 4
                npairs = struct.unpack('>i', code[p + 4:p + 8])[0]
                pc = p + 8 + 8 * npairs
                continue
            if op == 0xc4:  # wide
                pc += 6 if code[pc + 1] == 0x84 else 4
                continue
            if op not in size:
                raise ValueError('unknown opcode 0x%x' % op)
            pc += 1 + size[op]
        return out


class Jar:
    def __init__(self, path):
        self.z = zipfile.ZipFile(path)
        self.cache = {}

    def get(self, internal):
        if internal in self.cache:
            return self.cache[internal]
        try:
            ci = ClassInfo(self.z.read(internal + '.class'))
        except KeyError:
            ci = None
        self.cache[internal] = ci
        return ci

    def hierarchy(self, internal):
        seen = []
        todo = [internal]
        while todo:
            c = todo.pop(0)
            if not c or c in seen:
                continue
            seen.append(c)
            ci = self.get(c)
            if ci:
                todo.append(ci.super)
                todo.extend(ci.interfaces)
        return seen

# ----------------------------------------------------------------------------------------------
# Java source parsing (regex level; good enough for this code base's mixin style)


PRIMS = {'void': 'V', 'boolean': 'Z', 'byte': 'B', 'char': 'C', 'short': 'S', 'int': 'I', 'long': 'J',
         'float': 'F', 'double': 'D'}


def strip_comments(s):
    s = re.sub(r'/\*.*?\*/', lambda m: '\n' * m.group(0).count('\n'), s, flags=re.S)
    return re.sub(r'//[^\n]*', '', s)


def find_annotations(src, name):
    """yield (start, end, body) for @name(...) with balanced parentheses."""
    for m in re.finditer(r'@' + name + r'\s*\(', src):
        i = m.end()
        depth = 1
        while depth:
            c = src[i]
            if c == '(':
                depth += 1
            elif c == ')':
                depth -= 1
            elif c == '"':
                i = src.index('"', i + 1)
            i += 1
        yield m.start(), i, src[m.end():i - 1]


# static final String constants of the file being checked (e.g. method = FORGE_CTOR)
CONSTS = {}


def ann_str(body, key):
    m = re.search(r'\b' + key + r'\s*=\s*(\{[^}]*\}|"[^"]*"|[A-Z_][A-Z0-9_]*\b)', body)
    if not m:
        if key == 'value':
            # positional form, e.g. @At("RETURN")
            p = re.match(r'\s*"([^"]*)"', body)
            return [p.group(1)] if p else None
        return None
    v = m.group(1)
    if v in CONSTS:
        return [CONSTS[v]]
    return re.findall(r'"([^"]*)"', v)


def ann_int(body, key):
    m = re.search(r'\b' + key + r'\s*=\s*(-?\d+)', body)
    return int(m.group(1)) if m else None


def split_ats(body):
    ats = []
    for s, e, b in find_annotations(body, 'At'):
        ats.append(b)
    return ats


def parse_method_ref(ref):
    """'name(desc)ret' or 'name' or 'Lowner;name(desc)' -> (owner, name, desc)"""
    m = re.match(r'^(?:L([\w/$]+);)?([\w<>$*]+)(\(.*)?$', ref)
    if not m:
        return None, ref, None
    return m.group(1), m.group(2), m.group(3)



JAVA_LANG = {'String', 'Object', 'Iterable', 'Runnable', 'Class', 'Thread', 'CharSequence', 'Integer', 'Long',
             'Float', 'Double', 'Boolean', 'Byte', 'Short', 'Character', 'Void', 'Throwable', 'Exception',
             'Enum', 'Comparable', 'Number', 'StringBuilder', 'AutoCloseable'}


def type_desc(t, imports):
    """Java source type -> JVM descriptor, or None if it cannot be resolved from imports."""
    t = re.sub(r'@\w+(\([^)]*\))?\s*', '', t).strip()
    t = re.sub(r'\bfinal\s+', '', t).strip()
    dims = 0
    if t.endswith('...'):
        dims += 1
        t = t[:-3].strip()
    while t.endswith('[]'):
        dims += 1
        t = t[:-2].strip()
    # erase generics
    depth, out = 0, ''
    for ch in t:
        if ch == '<':
            depth += 1
        elif ch == '>':
            depth -= 1
        elif depth == 0:
            out += ch
    t = out.strip()
    if t in PRIMS:
        d = PRIMS[t]
    else:
        parts = t.split('.')
        if parts[0] in imports:
            d = 'L' + imports[parts[0]] + ''.join('$' + x for x in parts[1:]) + ';'
        elif t in JAVA_LANG:
            d = 'Ljava/lang/' + t + ';'
        elif len(parts) > 1 and parts[0][:1].islower():
            # fully qualified; inner classes are written Outer.Inner -> guess first capitalised segment is the class
            k = next(i for i, x in enumerate(parts) if x[:1].isupper())
            d = 'L' + '/'.join(parts[:k + 1]) + ''.join('$' + x for x in parts[k + 1:]) + ';'
        else:
            return None
    return '[' * dims + d


def params_desc(params, imports):
    params = params.strip()
    if not params:
        return '()'
    # split on commas not inside generics
    depth, cur, items = 0, '', []
    for ch in params:
        if ch == '<':
            depth += 1
        elif ch == '>':
            depth -= 1
        if ch == ',' and depth == 0:
            items.append(cur)
            cur = ''
        else:
            cur += ch
    items.append(cur)
    descs = []
    for it in items:
        it = it.strip()
        typ = it.rsplit(None, 1)[0] if ' ' in it else it
        d = type_desc(typ, imports)
        if d is None:
            return None
        descs.append(d)
    return '(' + ''.join(descs) + ')'


# ----------------------------------------------------------------------------------------------

def main():
    jar = Jar(sys.argv[1])
    root = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'java')
    mixin_dir = os.path.join(root, 'net', 'vulkanmod', 'mixin')
    problems = []
    checked = 0

    for dirpath, _, files in os.walk(mixin_dir):
        for f in files:
            if not f.endswith('.java'):
                continue
            path = os.path.join(dirpath, f)
            raw = open(path, encoding='utf-8').read()
            src = strip_comments(raw)
            rel = os.path.relpath(path, root).replace('\\', '/')

            imports = dict((m.group(2), m.group(1).replace('.', '/'))
                           for m in re.finditer(r'import\s+([\w.]+\.(\w+))\s*;', src))
            CONSTS.clear()
            CONSTS.update(dict((m.group(1), m.group(2)) for m in
                               re.finditer(r'static\s+final\s+String\s+(\w+)\s*=\s*"([^"]*)"\s*;', src)))
            starts = [m.start() for m in re.finditer(r'@Mixin\s*\(', src)]
            for k, st in enumerate(starts):
                seg = src[st:starts[k + 1] if k + 1 < len(starts) else len(src)]
                problems_seg, checked_seg = check_segment(jar, seg, imports, rel)
                problems += problems_seg
                checked += checked_seg

    for p in problems:
        print('PROBLEM', p)
    print('checked %d references, %d problems' % (checked, len(problems)))
    sys.exit(1 if problems else 0)


def check_segment(jar, src, imports, rel):
    problems = []
    checked = 0
    if True:
        if True:
            mm = re.search(r'@Mixin\s*\(\s*(?:value\s*=\s*)?\{?\s*([\w.$]+?)\.class', src)
            if not mm:
                return problems, checked
            tname = mm.group(1)
            parts = tname.split('.')
            if parts[0] in imports:
                internal = imports[parts[0]] + ''.join('$' + p for p in parts[1:])
            else:
                internal = tname.replace('.', '/')
            target = jar.get(internal)
            if target is None:
                if internal.startswith('net/minecraft') or internal.startswith('com/mojang'):
                    problems.append('%s: target class %s not found' % (rel, internal))
                return problems, checked
            hier = jar.hierarchy(internal)

            def methods_named(name):
                res = []
                for c in hier:
                    ci = jar.get(c)
                    if ci:
                        res += [(c, k) for k in ci.methods if k[0] == name]
                return res

            # ---- @Overwrite / @Shadow methods
            for m in re.finditer(r'@(Overwrite|Shadow)\b(\s*\([^)]*\))?[^;{]*?\s(\w+)\s*\(([^)]*)\)\s*(?:throws [\w., ]+)?[;{]', src):
                kind, name = m.group(1), m.group(3)
                checked += 1
                found = methods_named(name)
                if not found:
                    problems.append('%s: @%s method %s not found in %s' % (rel, kind, name, internal))
                    continue
                # An @Overwrite whose descriptor no longer matches does not fail loudly: the vanilla
                # method stays in place. Compare parameter descriptors where types can be resolved.
                pd = params_desc(m.group(4), imports)
                if pd is not None and not any(k[1].startswith(pd) for _, k in found):
                    problems.append('%s: @%s method %s%s has no descriptor match in %s (have: %s)'
                                    % (rel, kind, name, pd, internal,
                                       ', '.join(sorted(set(k[1] for _, k in found)))))
            for m in re.finditer(r'@Shadow\b(?:\s*\([^)]*\))?((?:\s*@\w+(?:\([^)]*\))?)*)\s+(?:(?:private|public|protected|static|final|abstract)\s+)*([\w.<>\[\], ?]+?)\s+(\w+)\s*;', src):
                name = m.group(3)
                checked += 1
                found = any(jar.get(c) and name in jar.get(c).fields for c in hier)
                if not found:
                    problems.append('%s: @Shadow field %s not found in %s' % (rel, name, internal))

            # ---- injectors
            for ann in ('Inject', 'Redirect', 'ModifyArg', 'ModifyArgs', 'ModifyVariable', 'ModifyConstant', 'WrapOperation'):
                for s, e, body in find_annotations(src, ann):
                    meths = ann_str(body, 'method') or []
                    ats = split_ats(body)
                    for mref in meths:
                        checked += 1
                        _, mname, mdesc = parse_method_ref(mref)
                        wildcard = mname.endswith('*')
                        mname = mname.rstrip('*')
                        if mname == '':
                            # method = "*": every method of the target class
                            cands = [(internal, k) for k in target.methods]
                        else:
                            cands = [(c, k) for (c, k) in methods_named(mname) if c == internal]
                        if mdesc:
                            cands = [x for x in cands if x[1][1] == mdesc]
                        elif not wildcard:
                            cands = cands[:1]  # Mixin: a bare name selects the first matching method only
                        if not cands:
                            problems.append('%s: @%s target method %s not found in %s' % (rel, ann, mref, internal))
                            continue
                        # ---- handler signature (Mixin validates it at apply time and crashes on mismatch)
                        hm = re.match(r'\s*(?:(?:private|public|protected|static|final)\s+)*[\w<>\[\], .?@]+?\s+([\w$]+)\s*\(([^)]*)\)',
                                      src[e:])
                        if hm and ann == 'Inject' and len(cands) == 1:
                            hp = params_desc(hm.group(2), imports)
                            if hp is not None:
                                hargs = re.findall(r'\[*(?:L[^;]+;|[ZBCSIJFD])', hp[1:-1])
                                ci_idx = next((i for i, a in enumerate(hargs)
                                               if 'mixin/injection/callback/CallbackInfo' in a), None)
                                targs = re.findall(r'\[*(?:L[^;]+;|[ZBCSIJFD])', cands[0][1][1].split(')')[0][1:])
                                if ci_idx is not None and ci_idx > 0 and hargs[:ci_idx] != targs:
                                    problems.append('%s: @Inject handler %s args %s do not match target %s%s'
                                                    % (rel, hm.group(1), ''.join(hargs[:ci_idx]), mname, cands[0][1][1]))
                        if hm and ann == 'Redirect' and len(cands) == 1:
                            hp = params_desc(hm.group(2), imports)
                            at0 = ats[0] if ats else ''
                            v0 = (ann_str(at0, 'value') or [''])[0]
                            t0 = (ann_str(at0, 'target') or [''])[0]
                            if hp is not None and v0 == 'INVOKE' and '(' in t0:
                                hargs = re.findall(r'\[*(?:L[^;]+;|[ZBCSIJFD])', hp[1:-1])
                                owner = t0[1:t0.index(';')]
                                iargs = re.findall(r'\[*(?:L[^;]+;|[ZBCSIJFD])', t0[t0.index('(') + 1:t0.index(')')])
                                static = any(ins[0] == 'INVOKE' and 'L%s;%s%s' % (ins[1], ins[2], ins[3]) == t0 and ins[4] == 0xb8
                                             for ins in jar.get(cands[0][0]).methods[cands[0][1]])
                                expected = iargs if static else ['L%s;' % owner] + iargs
                                if hargs[:len(expected)] != expected:
                                    problems.append('%s: @Redirect handler %s args %s do not match %s (expected %s)'
                                                    % (rel, hm.group(1), ''.join(hargs), t0, ''.join(expected)))
                        if ann == 'Inject' and mname == '<init>':
                            for at in ats:
                                v = (ann_str(at, 'value') or [''])[0]
                                if v not in ('RETURN', 'TAIL'):
                                    problems.append('%s: @Inject into a constructor at %s; upstream Mixin 0.8.5 (Forge) '
                                                    'only supports RETURN/TAIL there' % (rel, v))
                        for at in ats:
                            value = (ann_str(at, 'value') or [''])[0]
                            tgt = ann_str(at, 'target')
                            ordinal = ann_int(at, 'ordinal')
                            if value not in ('INVOKE', 'FIELD', 'NEW', 'INVOKE_ASSIGN', 'INVOKE_STRING') or not tgt:
                                continue
                            t = tgt[0]
                            hits = 0
                            for c, k in cands:
                                for ins in jar.get(c).methods[k]:
                                    kind, owner, name, desc, op = ins
                                    if value in ('INVOKE', 'INVOKE_ASSIGN', 'INVOKE_STRING') and kind == 'INVOKE':
                                        if t == 'L%s;%s%s' % (owner, name, desc):
                                            hits += 1
                                    elif value == 'FIELD' and kind == 'FIELD':
                                        if t == 'L%s;%s:%s' % (owner, name, desc):
                                            hits += 1
                                    elif value == 'NEW' and kind == 'NEW':
                                        tt = t[1:-1] if t.startswith('L') and t.endswith(';') else t
                                        tt = tt.split('(')[0]
                                        if tt == owner:
                                            hits += 1
                            if hits == 0:
                                problems.append('%s: @%s(%s) @At(%s) target %s not found in %s.%s'
                                                % (rel, ann, mref, value, t, internal, mname))
                            elif ordinal is not None and ordinal >= hits // max(1, len(cands)) and ordinal >= hits:
                                problems.append('%s: @%s(%s) ordinal %d out of range (%d matches) for %s'
                                                % (rel, ann, mref, ordinal, hits, t))

    return problems, checked


if __name__ == '__main__':
    main()
