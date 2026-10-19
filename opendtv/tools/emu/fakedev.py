import struct

def cksum(b):
    s = 0
    for i, x in enumerate(b):
        s += (x << 8) if (i & 1) == 0 else x
    s &= 0xFFFF
    return (~s) & 0xFFFF

class FakeDev:
    """Minimal Afatech IT9175 command-channel model. Logs every frame."""
    def __init__(self):
        self.vt = 0  # virtual time in us
        self.regs = {}      # (proc, addr) -> byte
        self.defaults = {}  # (proc, addr) -> byte
        self.resp = []      # queued response frames
        self.trace = []     # (kind, ...)
        self.fw_started = False
        self.fw_bytes = 0
        self.tick = 0
        self.on_read = {}   # (proc,addr) -> callable returning byte

    def frame_resp(self, seq, status, data=b''):
        body = bytes([seq, status]) + bytes(data)
        f = bytes([len(body) + 2 + 1 - 1 + 0])  # placeholder
        total = 1 + len(body) + 2
        out = bytearray([total - 1]) + body
        cs = cksum(body)
        out += bytes([cs >> 8, cs & 0xFF])
        return bytes(out)

    def bulk_out(self, ep, payload):
        if ep != 2:
            self.trace.append(('OUT?', ep, payload.hex())); return
        n = payload[0] + 1
        f = payload[:n]
        proc = f[1] >> 4
        cmd = ((f[1] & 0xF) << 8) | f[2]
        seq = f[3]
        # verify checksum
        ok = cksum(f[1:-2]) == ((f[-2] << 8) | f[-1])
        if cmd == 0:  # read regs
            cnt = f[4]; alen = f[5]; addr = struct.unpack('>I', f[6:10])[0]
            data = bytes(self.rd(proc, addr + i) for i in range(cnt))
            self.trace.append(('R', proc, addr, cnt, data.hex(), ok))
            self.resp.append(self.frame_resp(seq, 0, data))
        elif cmd == 1:  # write regs
            cnt = f[4]; alen = f[5]; addr = struct.unpack('>I', f[6:10])[0]
            data = f[10:10 + cnt]
            for i, b in enumerate(data): self.regs[(proc, addr + i)] = b
            self.trace.append(('W', proc, addr, cnt, bytes(data).hex(), ok))
            self.resp.append(self.frame_resp(seq, 0))
        else:
            pl = f[4:-2]
            self.trace.append(('C', proc, hex(cmd), len(pl), pl[:16].hex(), ok))
            data = b''
            if cmd == 0x22:   # fw version
                data = bytes([3, 0, 4, 6]) if self.fw_started else bytes(4)
            elif cmd == 0x23:
                self.fw_started = True
            elif cmd == 0x29:
                self.fw_bytes += len(pl)
            self.resp.append(self.frame_resp(seq, 0, data))

    def rd(self, proc, addr):
        k = (proc, addr)
        if k in self.on_read: return self.on_read[k](self)
        if k in self.regs: return self.regs[k]
        return self.defaults.get(k, 0)

    def bulk_in(self, ep, ln):
        if ep == 0x81:
            if not self.resp: return None
            r = self.resp.pop(0)
            return r[:ln]
        if ep == 0x84:
            self.tick += 1
            return bytes([0x47, 0x1f, 0xff, 0x10]) + bytes(184) if self.regs.get((8, 0xf99d), 0) is not None else None
        return None
