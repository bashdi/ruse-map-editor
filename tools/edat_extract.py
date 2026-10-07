import sys
from edat import read_edat
f, files = read_edat(sys.argv[1])
for n,o,s in files:
    if sys.argv[2] in n:
        f.seek(o); data=f.read(s)
        out = sys.argv[3]
        open(out,'wb').write(data); print(n, s); break
