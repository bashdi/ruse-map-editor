import struct, sys

def read_edat(path):
    f = open(path, 'rb')
    h = f.read(0x40)
    assert h[:4] == b'edat'
    doff, dlen, foff, flen = struct.unpack_from('<IIII', h, 0x19)
    f.seek(doff)
    d = f.read(dlen)
    pos = 0
    stack = []  # (name, nextSibling)
    files = []
    while pos < dlen:
        gid, esz = struct.unpack_from('<II', d, pos)
        if gid == 0:
            off, size = struct.unpack_from('<II', d, pos + 8)
            chk = d[pos + 16]
            end = d.index(b'\0', pos + 17)
            name = d[pos + 17:end].decode('latin1')
            full = ''.join(s[0] for s in stack) + name
            files.append((full, off + foff, size))
            hdr = end + 1 - pos
            if hdr % 2: hdr += 1
            pos += hdr
            if esz == 0:
                while stack and stack[-1][1] == 0:
                    stack.pop()
                if stack:
                    stack.pop()
        else:
            end = d.index(b'\0', pos + 8)
            name = d[pos + 8:end].decode('latin1')
            pos += gid
            stack.append((name, esz))
    return f, files

if __name__ == '__main__':
    f, files = read_edat(sys.argv[1])
    for n, o, s in files:
        print(f'{o:12d} {s:12d} {n}')
