import sys,struct,json
sys.path.insert(0,'.')
from run_tune import build
BLACK={(0,0xd800),(8,0x1dc),(8,0xec86),(0,0x1222),(0,0x1223),(8,0x45),(8,0x1c6),(8,0x4d)}
def base(clk,d1dc=0,ec86=0):
    return {(8,0xec86):ec86,(8,0xed23):0xe0,(8,0xed24):0x2e,(0,0xd800):clk,(8,0x1dc):d1dc}
def run(clk,hooks,tune=None,d1dc=0,ec86=0):
    e,dev,ops=build(base(clk,d1dc,ec86))
    for k,v in hooks.items(): dev.on_read[k]=(lambda v: lambda d: v)(v)
    e.call(e.syms['lib275a_Init'],[ops])
    n0=len(dev.trace)
    if tune:
        p=e.h_malloc(64); e.uc.mem_write(p,struct.pack('<IIII',tune,0,2,0)); e.call(e.syms['lib275a_SetPara'],[p])
    return [t for t in dev.trace if not (t[0]=='R' and t[2]==71)], n0
def pairs(t):
    """list of items: ('W',p,a,hex) ('RMW',p,a,idxR) ('V',p) ('POLL',p,a) ('R',..) plain; with source indexes"""
    items=[];i=0
    while i<len(t):
        x=t[i]
        if x[0]=='R':
            j=i
            while j+1<len(t) and t[j+1][:3]==x[:3]: j+=1
            if j-i>=2: items.append(('POLL',x[1],x[2],i)); i=j+1; continue
            if i+1<len(t) and t[i+1][0]=='W' and t[i+1][1:4]==x[1:4] and x[3]==1:
                items.append(('RMW',x[1],x[2],i)); i+=2; continue
            items.append(('R',x[1],x[2],x[3],i)); i+=1; continue
        if x[0]=='W': items.append(('W',x[1],x[2],x[4],i))
        elif x[0]=='C': items.append(('C',x[1],x[2],x[4],i))
        i+=1
    return items
def program(clk,tune=None,d1dc=0,ec86=0):
    tA,nA=run(clk,{},tune,d1dc,ec86)
    its=pairs(tA)
    tg={(it[1],it[2]) for it in its if it[0]=='RMW' and (it[1],it[2]) not in BLACK}
    h0={k:0 for k in tg}; h1={k:0xff for k in tg}
    t0,_=run(clk,h0,tune,d1dc,ec86); t1,_=run(clk,h1,tune,d1dc,ec86)
    assert len(t0)==len(t1)==len(tA) or print('LEN',len(tA),len(t0),len(t1))
    i0=pairs(t0); i1=pairs(t1)
    out=[]
    for a,b in zip(i0,i1):
        assert a[:3]==b[:3] or print('MISMATCH',a,b)
        k=a[0]
        if k=='W': out.append(('W',a[1],a[2],a[3]))
        elif k=='RMW':
            if (a[1],a[2]) in tg:
                wa=t0[a[3]+1][4]; wb=t1[a[3]+1][4]
                A=bytes.fromhex(wa)[0]; B=bytes.fromhex(wb)[0]
                out.append(('M',a[1],a[2],B&~A&0xff,A))
            else:
                out.append(('X',a[1],a[2],t0[a[3]][4],t0[a[3]+1][4]))   # decision-reg rmw
        elif k=='POLL': out.append(('P',a[1],a[2]))
        elif k=='C':
            if a[2]=='0x22': out.append(('V',a[1]))
            else: out.append(('C',a[1],a[2],a[3]))
        else: out.append(('R',a[1],a[2],a[3]))
    return out,tA,nA
if __name__=='__main__':
    for clk in (0,1):
        o,tA,nA=program(clk)
        print(clk,len(o),[x for x in o if x[0]=='X'][:5])
