import struct, sys, zlib

def load(path_or_bytes):
    d = path_or_bytes if isinstance(path_or_bytes, bytes) else open(path_or_bytes, 'rb').read()
    if d[8:12] == b'CNDF' and d[12] & 0x80:
        o = zlib.decompressobj()
        d = d[:0x28] + o.decompress(d[0x2c:])
    return Ndf(d)

class Ndf:
    def __init__(s, d, base=0):
        s.d = d
        toc = struct.unpack_from('<Q', d, 0x10)[0]
        assert d[toc:toc+4] == b'TOC0', d[toc:toc+4]
        n = struct.unpack_from('<I', d, toc+4)[0]
        s.tables = {}
        for i in range(n):
            o = toc + 8 + i*24
            s.tables[d[o:o+4].decode()] = struct.unpack_from('<QQ', d, o+8)
        s.classes = s.strlist('CLAS')
        s.strings = s.strlist('STRG')
        s.trans = s.strlist('TRAN')
        s.props = []
        off, size = s.tables['PROP']; p = off
        while p < off+size:
            l = struct.unpack_from('<I', d, p)[0]; name = d[p+4:p+4+l].decode('latin1')
            cls = struct.unpack_from('<I', d, p+4+l)[0]; s.props.append((name, cls)); p += 8+l
        s.objects = []
        off, size = s.tables['OBJE']; s.p = off; end = off+size
        while s.p < end:
            cls = s.u32(); props = []
            while True:
                pid = s.u32()
                if pid == 0xABABABAB: break
                props.append((pid, s.value()))
            s.objects.append((cls, props))

    def strlist(s, t):
        off, size = s.tables[t]; p = off; out = []
        while p < off+size:
            l = struct.unpack_from('<I', s.d, p)[0]; out.append(s.d[p+4:p+4+l].decode('latin1')); p += 4+l
        return out

    def u32(s):
        v = struct.unpack_from('<I', s.d, s.p)[0]; s.p += 4; return v
    def take(s, n):
        b = s.d[s.p:s.p+n]; s.p += n; return b

    def value(s):
        t = s.u32()
        return s.typed(t)

    def typed(s, t):
        f = s.d
        if t == 0: return ('bool', s.take(1)[0])
        if t == 1: return ('i8', s.take(1)[0])
        if t == 2: return ('i32', struct.unpack('<i', s.take(4))[0])
        if t == 3: return ('u32', s.u32())
        if t == 5: return ('f32', struct.unpack('<f', s.take(4))[0])
        if t == 6: return ('f64', struct.unpack('<d', s.take(8))[0])
        if t == 7: i = s.u32(); return ('str', s.strings[i])
        if t == 8: l = s.u32(); return ('wstr', s.take(l).decode('utf-16-le', 'replace'))
        if t == 9:
            k = s.u32()
            if k == 0xAAAAAAAA: return ('tranref', s.u32())
            if k == 0xBBBBBBBB: inst = s.u32(); cls = s.u32(); return ('objref', inst, cls)
            raise Exception('ref kind %x at %x' % (k, s.p))
        if t == 0x0b: return ('vec3', struct.unpack('<3f', s.take(12)))
        if t == 0x0c: return ('color', s.take(4).hex())
        if t == 0x0d: return ('color128', struct.unpack('<4f', s.take(16)))
        if t == 0x11:
            n = s.u32(); return ('list', [s.value() for _ in range(n)])
        if t == 0x12:
            n = s.u32(); return ('map', [(s.value(), s.value()) for _ in range(n)])
        if t == 0x13: return ('i64', struct.unpack('<q', s.take(8))[0])
        if t == 0x14: l = s.u32(); return ('blob', s.take(l).hex()[:64])
        if t == 0x18: return ('i16', struct.unpack('<h', s.take(2))[0])
        if t == 0x19: return ('u16', struct.unpack('<H', s.take(2))[0])
        if t == 0x1a: return ('guid', s.take(16).hex())
        if t == 0x1c: i = s.u32(); return ('path', s.strings[i])
        if t == 0x1d: return ('loc', s.take(8).hex())
        raise Exception('unknown type %x at %x' % (t, s.p - 4))

    def fmt(s, v, ind=0):
        k = v[0]
        if k == 'list': return '[' + ', '.join(s.fmt(x) for x in v[1]) + ']'
        if k == 'map': return '{' + ', '.join(s.fmt(a) + ': ' + s.fmt(b) for a, b in v[1]) + '}'
        if k == 'objref': return '#%d(%s)' % (v[1], s.classes[v[2]] if v[2] < len(s.classes) else v[2])
        if k == 'tranref': return '@' + (s.trans[v[1]] if v[1] < len(s.trans) else str(v[1]))
        return repr(v[1])

    def dump(s, out=sys.stdout):
        for i, (cls, props) in enumerate(s.objects):
            print('#%d %s' % (i, s.classes[cls]), file=out)
            for pid, v in props:
                print('   %s = %s' % (s.props[pid][0], s.fmt(v)), file=out)

if __name__ == '__main__':
    n = load(sys.argv[1])
    n.dump()
