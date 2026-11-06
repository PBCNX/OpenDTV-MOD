import sys,struct,time,json
sys.path.insert(0,'.')
from run_tune import *
def make(d1dc):
    d={(8,0xec86):0,(8,0xed23):0xe0,(8,0xed24):0x2e,(0,0xd800):1}
    e,dev,ops=build(d); e.call(e.syms['lib275a_Init'],[ops])
    dev.regs[(8,0x1dc)]=d1dc   # after init, set the register value
    p=e.h_malloc(64)
    def row(fk):
        n0=len(dev.trace)
        e.uc.mem_write(p,struct.pack('<IIII',fk*1000,0,2,0)); e.call(e.syms['lib275a_SetPara'],[p])
        w={}
        for t in dev.trace[n0:]:
            if t[0]=='W' and t[2] in (0xfb25,0xfbb5,0xfbb6,0xfbb7,0xfbb9,0xfbba): w.setdefault(t[2],[]).append(t[4])
        k=tuple((a,tuple(v)) for a,v in sorted(w.items()))
        del dev.trace[n0:]
        return k
    return row
if __name__=='__main__':
    for d1 in (0,1):
        row=make(d1); t=time.time()
        print(d1,row(497143), time.time()-t)
