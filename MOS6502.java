/**
 * MOS6502 - A MOS Technology 6502 Virtual Machine in a single Java class.
 *
 * Implements the full 6502 instruction set including all official opcodes,
 * addressing modes, and the BCD (Binary Coded Decimal) arithmetic mode.
 *
 * Usage:
 *   MOS6502 cpu = new MOS6502(65536);   // 64KB address space
 *   cpu.loadProgram(0x0600, myBytes);    // load program at $0600
 *   cpu.reset(0x0600);                  // set PC to start
 *   while (!cpu.isHalted()) cpu.step(); // run instruction-by-instruction
 *
 * The 64KB memory array is fully accessible via read(addr) / write(addr, val).
 * You can override those methods to attach memory-mapped I/O peripherals.
 *
 * @author Mario Gianota 13 March 2026
 */
public class MOS6502 {

    // -----------------------------------------------------------------------
    // Memory
    // -----------------------------------------------------------------------
    protected final int[] mem;

    public MOS6502(int memSize) {
        mem = new int[memSize];
    }

    /** Load a byte array into memory starting at the given address. */
    public void loadProgram(int startAddr, int... bytes) {
        for (int i = 0; i < bytes.length; i++)
            write(startAddr + i, bytes[i] & 0xFF);
    }

    // Override these for memory-mapped I/O
    protected int read(int addr)           { return mem[addr & 0xFFFF] & 0xFF; }
    protected void write(int addr, int val){ mem[addr & 0xFFFF] = val & 0xFF;  }

    // -----------------------------------------------------------------------
    // Registers
    // -----------------------------------------------------------------------
    public int A, X, Y;       // accumulator, index registers
    public int SP = 0xFF;     // stack pointer (points into page 1: $0100-$01FF)
    public int PC;            // program counter

    // Processor Status flags
    public boolean N, V, B, D, I, Z, C;  // negative, overflow, break, decimal,
                                          // interrupt-disable, zero, carry

    // -----------------------------------------------------------------------
    // Status helpers
    // -----------------------------------------------------------------------
    private int getP() {
        return (N ? 0x80 : 0)
             | (V ? 0x40 : 0)
             | 0x20               // unused bit always 1
             | (B ? 0x10 : 0)
             | (D ? 0x08 : 0)
             | (I ? 0x04 : 0)
             | (Z ? 0x02 : 0)
             | (C ? 0x01 : 0);
    }

    private void setP(int p) {
        N = (p & 0x80) != 0;
        V = (p & 0x40) != 0;
        B = (p & 0x10) != 0;
        D = (p & 0x08) != 0;
        I = (p & 0x04) != 0;
        Z = (p & 0x02) != 0;
        C = (p & 0x01) != 0;
    }

    private void setNZ(int val) {
        val &= 0xFF;
        N = (val & 0x80) != 0;
        Z = (val == 0);
    }

    // -----------------------------------------------------------------------
    // Stack
    // -----------------------------------------------------------------------
    private void push(int val) {
        write(0x0100 | SP, val & 0xFF);
        SP = (SP - 1) & 0xFF;
    }

    private int pop() {
        SP = (SP + 1) & 0xFF;
        return read(0x0100 | SP);
    }

    // -----------------------------------------------------------------------
    // Reset / NMI / IRQ
    // -----------------------------------------------------------------------
    private boolean halted = false;
    public boolean isHalted() { return halted; }

    /** Reset the CPU, loading PC from the reset vector at $FFFC/$FFFD. */
    public void reset() {
        A = X = Y = 0;
        SP = 0xFF;
        I = true;
        B = false;
        D = false;
        PC = read(0xFFFC) | (read(0xFFFD) << 8);
        halted = false;
    }

    /** Reset the CPU and set PC to a specific address (convenient for testing). */
    public void reset(int startPC) {
        A = X = Y = 0;
        SP = 0xFF;
        I = true;
        B = false;
        D = false;
        PC = startPC & 0xFFFF;
        halted = false;
    }

    /** Trigger a non-maskable interrupt. */
    public void nmi() {
        push(PC >> 8);
        push(PC & 0xFF);
        push(getP() & ~0x10); // B clear
        I = true;
        PC = read(0xFFFA) | (read(0xFFFB) << 8);
    }

    /** Trigger a maskable interrupt (ignored if I flag is set). */
    public void irq() {
        if (I) return;
        push(PC >> 8);
        push(PC & 0xFF);
        push(getP() & ~0x10); // B clear
        I = true;
        PC = read(0xFFFE) | (read(0xFFFF) << 8);
    }

    // -----------------------------------------------------------------------
    // Addressing-mode helpers  (all return a resolved memory address)
    // -----------------------------------------------------------------------
    private int immAddr()  { return PC++;                                        }
    private int zpAddr()   { return fetch() & 0xFF;                              }
    private int zpxAddr()  { return (fetch() + X) & 0xFF;                        }
    private int zpyAddr()  { return (fetch() + Y) & 0xFF;                        }
    private int absAddr()  { int lo=fetch(); return lo | (fetch()<<8);           }
    private int abxAddr()  { int base=absAddr(); return (base+X)&0xFFFF;         }
    private int abyAddr()  { int base=absAddr(); return (base+Y)&0xFFFF;         }
    private int indAddr()  {                                                      // JMP ($xxxx)
        int ptr = absAddr();
        int lo = read(ptr);
        // 6502 page-crossing bug: high byte wraps within page
        int hi = read((ptr & 0xFF00) | ((ptr+1) & 0xFF));
        return lo | (hi << 8);
    }
    private int izxAddr()  {                                                      // ($xx,X)
        int ptr = (fetch() + X) & 0xFF;
        return read(ptr) | (read((ptr+1) & 0xFF) << 8);
    }
    private int izyAddr()  {                                                      // ($xx),Y
        int ptr = fetch() & 0xFF;
        int base = read(ptr) | (read((ptr+1) & 0xFF) << 8);
        return (base + Y) & 0xFFFF;
    }

    private int fetch() { int v = read(PC); PC = (PC+1) & 0xFFFF; return v; }

    // -----------------------------------------------------------------------
    // ALU operations
    // -----------------------------------------------------------------------
    private void adc(int val) {
        if (D) { // BCD mode
            int lo = (A & 0x0F) + (val & 0x0F) + (C ? 1 : 0);
            int hi = (A >> 4)   + (val >> 4)   + (lo > 9 ? 1 : 0);
            if (lo > 9) lo -= 10;
            if (hi > 9) { hi -= 10; C = true; } else C = false;
            A = ((hi & 0x0F) << 4) | (lo & 0x0F);
            Z = (A == 0); N = (A & 0x80) != 0;
        } else {
            int result = A + val + (C ? 1 : 0);
            V = ((~(A ^ val) & (A ^ result) & 0x80) != 0);
            C = result > 0xFF;
            A = result & 0xFF;
            setNZ(A);
        }
    }

    private void sbc(int val) {
        if (D) { // BCD mode
            int lo = (A & 0x0F) - (val & 0x0F) - (C ? 0 : 1);
            int hi = (A >> 4)   - (val >> 4)   - (lo < 0 ? 1 : 0);
            if (lo < 0) lo += 10;
            if (hi < 0) { hi += 10; C = false; } else C = true;
            A = ((hi & 0x0F) << 4) | (lo & 0x0F);
            Z = (A == 0); N = (A & 0x80) != 0;
        } else {
            adc(val ^ 0xFF); // SBC = ADC with inverted operand
        }
    }

    private void cmp(int reg, int val) {
        int result = (reg - val) & 0xFF;
        C = reg >= val;
        setNZ(result);
    }

    private int asl(int val) {
        C = (val & 0x80) != 0;
        val = (val << 1) & 0xFF;
        setNZ(val);
        return val;
    }

    private int lsr(int val) {
        C = (val & 0x01) != 0;
        val = (val >> 1) & 0xFF;
        setNZ(val);
        return val;
    }

    private int rol(int val) {
        int oldC = C ? 1 : 0;
        C = (val & 0x80) != 0;
        val = ((val << 1) | oldC) & 0xFF;
        setNZ(val);
        return val;
    }

    private int ror(int val) {
        int oldC = C ? 0x80 : 0;
        C = (val & 0x01) != 0;
        val = ((val >> 1) | oldC) & 0xFF;
        setNZ(val);
        return val;
    }

    private void bit(int val) {
        N = (val & 0x80) != 0;
        V = (val & 0x40) != 0;
        Z = (A & val) == 0;
    }

    // -----------------------------------------------------------------------
    // Branch helper
    // -----------------------------------------------------------------------
    private void branch(boolean cond) {
        int offset = fetch();
        if (offset > 0x7F) offset -= 0x100; // signed
        if (cond) PC = (PC + offset) & 0xFFFF;
    }

    // -----------------------------------------------------------------------
    // Main execution step — execute ONE instruction
    // -----------------------------------------------------------------------
    public int step() {
        if (halted) return 0;

        int opcode = fetch();
        int addr, val;

        switch (opcode) {

            // ---- LDA -------------------------------------------------------
            case 0xA9: A = read(immAddr()); setNZ(A); return 2;
            case 0xA5: A = read(zpAddr());  setNZ(A); return 3;
            case 0xB5: A = read(zpxAddr()); setNZ(A); return 4;
            case 0xAD: A = read(absAddr()); setNZ(A); return 4;
            case 0xBD: A = read(abxAddr()); setNZ(A); return 4;
            case 0xB9: A = read(abyAddr()); setNZ(A); return 4;
            case 0xA1: A = read(izxAddr()); setNZ(A); return 6;
            case 0xB1: A = read(izyAddr()); setNZ(A); return 5;

            // ---- LDX -------------------------------------------------------
            case 0xA2: X = read(immAddr()); setNZ(X); return 2;
            case 0xA6: X = read(zpAddr());  setNZ(X); return 3;
            case 0xB6: X = read(zpyAddr()); setNZ(X); return 4;
            case 0xAE: X = read(absAddr()); setNZ(X); return 4;
            case 0xBE: X = read(abyAddr()); setNZ(X); return 4;

            // ---- LDY -------------------------------------------------------
            case 0xA0: Y = read(immAddr()); setNZ(Y); return 2;
            case 0xA4: Y = read(zpAddr());  setNZ(Y); return 3;
            case 0xB4: Y = read(zpxAddr()); setNZ(Y); return 4;
            case 0xAC: Y = read(absAddr()); setNZ(Y); return 4;
            case 0xBC: Y = read(abxAddr()); setNZ(Y); return 4;

            // ---- STA -------------------------------------------------------
            case 0x85: write(zpAddr(),  A); return 3;
            case 0x95: write(zpxAddr(), A); return 4;
            case 0x8D: write(absAddr(), A); return 4;
            case 0x9D: write(abxAddr(), A); return 5;
            case 0x99: write(abyAddr(), A); return 5;
            case 0x81: write(izxAddr(), A); return 6;
            case 0x91: write(izyAddr(), A); return 6;

            // ---- STX -------------------------------------------------------
            case 0x86: write(zpAddr(),  X); return 3;
            case 0x96: write(zpyAddr(), X); return 4;
            case 0x8E: write(absAddr(), X); return 4;

            // ---- STY -------------------------------------------------------
            case 0x84: write(zpAddr(),  Y); return 3;
            case 0x94: write(zpxAddr(), Y); return 4;
            case 0x8C: write(absAddr(), Y); return 4;

            // ---- Transfer --------------------------------------------------
            case 0xAA: X = A; setNZ(X); return 2;  // TAX
            case 0xA8: Y = A; setNZ(Y); return 2;  // TAY
            case 0xBA: X = SP; setNZ(X); return 2; // TSX
            case 0x8A: A = X; setNZ(A); return 2;  // TXA
            case 0x9A: SP = X; return 2;            // TXS (no flags)
            case 0x98: A = Y; setNZ(A); return 2;  // TYA

            // ---- Stack -----------------------------------------------------
            case 0x48: push(A); return 3;           // PHA
            case 0x08: push(getP() | 0x10); return 3; // PHP (B always set in push)
            case 0x68: A = pop(); setNZ(A); return 4;  // PLA
            case 0x28: setP(pop()); return 4;        // PLP

            // ---- Increment / Decrement -------------------------------------
            case 0xE8: X = (X+1)&0xFF; setNZ(X); return 2; // INX
            case 0xC8: Y = (Y+1)&0xFF; setNZ(Y); return 2; // INY
            case 0xCA: X = (X-1)&0xFF; setNZ(X); return 2; // DEX
            case 0x88: Y = (Y-1)&0xFF; setNZ(Y); return 2; // DEY

            case 0xE6: addr=zpAddr();  write(addr, val=((read(addr)+1)&0xFF)); setNZ(val); return 5;
            case 0xF6: addr=zpxAddr(); write(addr, val=((read(addr)+1)&0xFF)); setNZ(val); return 6;
            case 0xEE: addr=absAddr(); write(addr, val=((read(addr)+1)&0xFF)); setNZ(val); return 6;
            case 0xFE: addr=abxAddr(); write(addr, val=((read(addr)+1)&0xFF)); setNZ(val); return 7;

            case 0xC6: addr=zpAddr();  write(addr, val=((read(addr)-1)&0xFF)); setNZ(val); return 5;
            case 0xD6: addr=zpxAddr(); write(addr, val=((read(addr)-1)&0xFF)); setNZ(val); return 6;
            case 0xCE: addr=absAddr(); write(addr, val=((read(addr)-1)&0xFF)); setNZ(val); return 6;
            case 0xDE: addr=abxAddr(); write(addr, val=((read(addr)-1)&0xFF)); setNZ(val); return 7;

            // ---- ADC -------------------------------------------------------
            case 0x69: adc(read(immAddr())); return 2;
            case 0x65: adc(read(zpAddr()));  return 3;
            case 0x75: adc(read(zpxAddr())); return 4;
            case 0x6D: adc(read(absAddr())); return 4;
            case 0x7D: adc(read(abxAddr())); return 4;
            case 0x79: adc(read(abyAddr())); return 4;
            case 0x61: adc(read(izxAddr())); return 6;
            case 0x71: adc(read(izyAddr())); return 5;

            // ---- SBC -------------------------------------------------------
            case 0xE9: sbc(read(immAddr())); return 2;
            case 0xE5: sbc(read(zpAddr()));  return 3;
            case 0xF5: sbc(read(zpxAddr())); return 4;
            case 0xED: sbc(read(absAddr())); return 4;
            case 0xFD: sbc(read(abxAddr())); return 4;
            case 0xF9: sbc(read(abyAddr())); return 4;
            case 0xE1: sbc(read(izxAddr())); return 6;
            case 0xF1: sbc(read(izyAddr())); return 5;

            // ---- AND -------------------------------------------------------
            case 0x29: A &= read(immAddr()); setNZ(A); return 2;
            case 0x25: A &= read(zpAddr());  setNZ(A); return 3;
            case 0x35: A &= read(zpxAddr()); setNZ(A); return 4;
            case 0x2D: A &= read(absAddr()); setNZ(A); return 4;
            case 0x3D: A &= read(abxAddr()); setNZ(A); return 4;
            case 0x39: A &= read(abyAddr()); setNZ(A); return 4;
            case 0x21: A &= read(izxAddr()); setNZ(A); return 6;
            case 0x31: A &= read(izyAddr()); setNZ(A); return 5;

            // ---- ORA -------------------------------------------------------
            case 0x09: A |= read(immAddr()); setNZ(A); return 2;
            case 0x05: A |= read(zpAddr());  setNZ(A); return 3;
            case 0x15: A |= read(zpxAddr()); setNZ(A); return 4;
            case 0x0D: A |= read(absAddr()); setNZ(A); return 4;
            case 0x1D: A |= read(abxAddr()); setNZ(A); return 4;
            case 0x19: A |= read(abyAddr()); setNZ(A); return 4;
            case 0x01: A |= read(izxAddr()); setNZ(A); return 6;
            case 0x11: A |= read(izyAddr()); setNZ(A); return 5;

            // ---- EOR -------------------------------------------------------
            case 0x49: A ^= read(immAddr()); setNZ(A); return 2;
            case 0x45: A ^= read(zpAddr());  setNZ(A); return 3;
            case 0x55: A ^= read(zpxAddr()); setNZ(A); return 4;
            case 0x4D: A ^= read(absAddr()); setNZ(A); return 4;
            case 0x5D: A ^= read(abxAddr()); setNZ(A); return 4;
            case 0x59: A ^= read(abyAddr()); setNZ(A); return 4;
            case 0x41: A ^= read(izxAddr()); setNZ(A); return 6;
            case 0x51: A ^= read(izyAddr()); setNZ(A); return 5;

            // ---- ASL -------------------------------------------------------
            case 0x0A: A = asl(A); return 2;
            case 0x06: addr=zpAddr();  write(addr, asl(read(addr))); return 5;
            case 0x16: addr=zpxAddr(); write(addr, asl(read(addr))); return 6;
            case 0x0E: addr=absAddr(); write(addr, asl(read(addr))); return 6;
            case 0x1E: addr=abxAddr(); write(addr, asl(read(addr))); return 7;

            // ---- LSR -------------------------------------------------------
            case 0x4A: A = lsr(A); return 2;
            case 0x46: addr=zpAddr();  write(addr, lsr(read(addr))); return 5;
            case 0x56: addr=zpxAddr(); write(addr, lsr(read(addr))); return 6;
            case 0x4E: addr=absAddr(); write(addr, lsr(read(addr))); return 6;
            case 0x5E: addr=abxAddr(); write(addr, lsr(read(addr))); return 7;

            // ---- ROL -------------------------------------------------------
            case 0x2A: A = rol(A); return 2;
            case 0x26: addr=zpAddr();  write(addr, rol(read(addr))); return 5;
            case 0x36: addr=zpxAddr(); write(addr, rol(read(addr))); return 6;
            case 0x2E: addr=absAddr(); write(addr, rol(read(addr))); return 6;
            case 0x3E: addr=abxAddr(); write(addr, rol(read(addr))); return 7;

            // ---- ROR -------------------------------------------------------
            case 0x6A: A = ror(A); return 2;
            case 0x66: addr=zpAddr();  write(addr, ror(read(addr))); return 5;
            case 0x76: addr=zpxAddr(); write(addr, ror(read(addr))); return 6;
            case 0x6E: addr=absAddr(); write(addr, ror(read(addr))); return 6;
            case 0x7E: addr=abxAddr(); write(addr, ror(read(addr))); return 7;

            // ---- BIT -------------------------------------------------------
            case 0x24: bit(read(zpAddr()));  return 3;
            case 0x2C: bit(read(absAddr())); return 4;

            // ---- CMP / CPX / CPY -------------------------------------------
            case 0xC9: cmp(A, read(immAddr())); return 2;
            case 0xC5: cmp(A, read(zpAddr()));  return 3;
            case 0xD5: cmp(A, read(zpxAddr())); return 4;
            case 0xCD: cmp(A, read(absAddr())); return 4;
            case 0xDD: cmp(A, read(abxAddr())); return 4;
            case 0xD9: cmp(A, read(abyAddr())); return 4;
            case 0xC1: cmp(A, read(izxAddr())); return 6;
            case 0xD1: cmp(A, read(izyAddr())); return 5;

            case 0xE0: cmp(X, read(immAddr())); return 2;
            case 0xE4: cmp(X, read(zpAddr()));  return 3;
            case 0xEC: cmp(X, read(absAddr())); return 4;

            case 0xC0: cmp(Y, read(immAddr())); return 2;
            case 0xC4: cmp(Y, read(zpAddr()));  return 3;
            case 0xCC: cmp(Y, read(absAddr())); return 4;

            // ---- Branches --------------------------------------------------
            case 0x90: branch(!C); return 2;  // BCC
            case 0xB0: branch( C); return 2;  // BCS
            case 0xF0: branch( Z); return 2;  // BEQ
            case 0xD0: branch(!Z); return 2;  // BNE
            case 0x30: branch( N); return 2;  // BMI
            case 0x10: branch(!N); return 2;  // BPL
            case 0x70: branch( V); return 2;  // BVS
            case 0x50: branch(!V); return 2;  // BVC

            // ---- Jumps -----------------------------------------------------
            case 0x4C: PC = absAddr(); return 3;          // JMP abs
            case 0x6C: PC = indAddr(); return 5;          // JMP ind

            case 0x20: {                                   // JSR
                int target = absAddr();
                int ret = (PC - 1) & 0xFFFF;
                push(ret >> 8);
                push(ret & 0xFF);
                PC = target;
                return 6;
            }

            case 0x60: {                                   // RTS
                int lo = pop();
                int hi = pop();
                PC = ((lo | (hi << 8)) + 1) & 0xFFFF;
                return 6;
            }

            case 0x40: {                                   // RTI
                setP(pop());
                int lo = pop();
                int hi = pop();
                PC = lo | (hi << 8);
                return 6;
            }

            // ---- BRK -------------------------------------------------------
            case 0x00: {
                PC = (PC + 1) & 0xFFFF; // skip padding byte
                push(PC >> 8);
                push(PC & 0xFF);
                push(getP() | 0x10);    // B set
                I = true;
                PC = read(0xFFFE) | (read(0xFFFF) << 8);
                return 7;
            }

            // ---- Flag instructions -----------------------------------------
            case 0x18: C = false; return 2;  // CLC
            case 0x38: C = true;  return 2;  // SEC
            case 0x58: I = false; return 2;  // CLI
            case 0x78: I = true;  return 2;  // SEI
            case 0xB8: V = false; return 2;  // CLV
            case 0xD8: D = false; return 2;  // CLD
            case 0xF8: D = true;  return 2;  // SED

            // ---- NOP -------------------------------------------------------
            case 0xEA: return 2;

            // ---- Unofficial / undocumented NOPs (safe to consume) ----------
            case 0x1A: case 0x3A: case 0x5A: case 0x7A: case 0xDA: case 0xFA:
                return 2; // 1-byte NOPs
            case 0x80: case 0x82: case 0x89: case 0xC2: case 0xE2:
                fetch(); return 2; // 2-byte NOPs (skip immediate)
            case 0x04: case 0x44: case 0x64:
                fetch(); return 3; // 2-byte NOPs (skip zero page)
            case 0x14: case 0x34: case 0x54: case 0x74: case 0xD4: case 0xF4:
                fetch(); return 4; // 2-byte NOPs (skip zp,x)
            case 0x0C:
                absAddr(); return 4; // 3-byte NOP
            case 0x1C: case 0x3C: case 0x5C: case 0x7C: case 0xDC: case 0xFC:
                abxAddr(); return 4; // 3-byte NOPs

            // ---- Unofficial: LAX (LDA + LDX) --------------------------------
            case 0xA7: val=read(zpAddr());  A=X=val; setNZ(A); return 3;
            case 0xB7: val=read(zpyAddr()); A=X=val; setNZ(A); return 4;
            case 0xAF: val=read(absAddr()); A=X=val; setNZ(A); return 4;
            case 0xBF: val=read(abyAddr()); A=X=val; setNZ(A); return 4;
            case 0xA3: val=read(izxAddr()); A=X=val; setNZ(A); return 6;
            case 0xB3: val=read(izyAddr()); A=X=val; setNZ(A); return 5;

            // ---- Unofficial: SAX (store A & X) ------------------------------
            case 0x87: write(zpAddr(),  A & X); return 3;
            case 0x97: write(zpyAddr(), A & X); return 4;
            case 0x8F: write(absAddr(), A & X); return 4;
            case 0x83: write(izxAddr(), A & X); return 6;

            // ---- Unofficial: DCP (DEC then CMP) -----------------------------
            case 0xC7: addr=zpAddr();  val=(read(addr)-1)&0xFF; write(addr,val); cmp(A,val); return 5;
            case 0xD7: addr=zpxAddr(); val=(read(addr)-1)&0xFF; write(addr,val); cmp(A,val); return 6;
            case 0xCF: addr=absAddr(); val=(read(addr)-1)&0xFF; write(addr,val); cmp(A,val); return 6;
            case 0xDF: addr=abxAddr(); val=(read(addr)-1)&0xFF; write(addr,val); cmp(A,val); return 7;
            case 0xDB: addr=abyAddr(); val=(read(addr)-1)&0xFF; write(addr,val); cmp(A,val); return 7;
            case 0xC3: addr=izxAddr(); val=(read(addr)-1)&0xFF; write(addr,val); cmp(A,val); return 8;
            case 0xD3: addr=izyAddr(); val=(read(addr)-1)&0xFF; write(addr,val); cmp(A,val); return 8;

            // ---- Unofficial: ISB/ISC (INC then SBC) -------------------------
            case 0xE7: addr=zpAddr();  val=(read(addr)+1)&0xFF; write(addr,val); sbc(val); return 5;
            case 0xF7: addr=zpxAddr(); val=(read(addr)+1)&0xFF; write(addr,val); sbc(val); return 6;
            case 0xEF: addr=absAddr(); val=(read(addr)+1)&0xFF; write(addr,val); sbc(val); return 6;
            case 0xFF: addr=abxAddr(); val=(read(addr)+1)&0xFF; write(addr,val); sbc(val); return 7;
            case 0xFB: addr=abyAddr(); val=(read(addr)+1)&0xFF; write(addr,val); sbc(val); return 7;
            case 0xE3: addr=izxAddr(); val=(read(addr)+1)&0xFF; write(addr,val); sbc(val); return 8;
            case 0xF3: addr=izyAddr(); val=(read(addr)+1)&0xFF; write(addr,val); sbc(val); return 8;

            // ---- Unofficial: SLO (ASL then ORA) -----------------------------
            case 0x07: addr=zpAddr();  val=asl(read(addr)); write(addr,val); A|=val; setNZ(A); return 5;
            case 0x17: addr=zpxAddr(); val=asl(read(addr)); write(addr,val); A|=val; setNZ(A); return 6;
            case 0x0F: addr=absAddr(); val=asl(read(addr)); write(addr,val); A|=val; setNZ(A); return 6;
            case 0x1F: addr=abxAddr(); val=asl(read(addr)); write(addr,val); A|=val; setNZ(A); return 7;
            case 0x1B: addr=abyAddr(); val=asl(read(addr)); write(addr,val); A|=val; setNZ(A); return 7;
            case 0x03: addr=izxAddr(); val=asl(read(addr)); write(addr,val); A|=val; setNZ(A); return 8;
            case 0x13: addr=izyAddr(); val=asl(read(addr)); write(addr,val); A|=val; setNZ(A); return 8;

            // ---- Unofficial: RLA (ROL then AND) -----------------------------
            case 0x27: addr=zpAddr();  val=rol(read(addr)); write(addr,val); A&=val; setNZ(A); return 5;
            case 0x37: addr=zpxAddr(); val=rol(read(addr)); write(addr,val); A&=val; setNZ(A); return 6;
            case 0x2F: addr=absAddr(); val=rol(read(addr)); write(addr,val); A&=val; setNZ(A); return 6;
            case 0x3F: addr=abxAddr(); val=rol(read(addr)); write(addr,val); A&=val; setNZ(A); return 7;
            case 0x3B: addr=abyAddr(); val=rol(read(addr)); write(addr,val); A&=val; setNZ(A); return 7;
            case 0x23: addr=izxAddr(); val=rol(read(addr)); write(addr,val); A&=val; setNZ(A); return 8;
            case 0x33: addr=izyAddr(); val=rol(read(addr)); write(addr,val); A&=val; setNZ(A); return 8;

            // ---- Unofficial: SRE (LSR then EOR) -----------------------------
            case 0x47: addr=zpAddr();  val=lsr(read(addr)); write(addr,val); A^=val; setNZ(A); return 5;
            case 0x57: addr=zpxAddr(); val=lsr(read(addr)); write(addr,val); A^=val; setNZ(A); return 6;
            case 0x4F: addr=absAddr(); val=lsr(read(addr)); write(addr,val); A^=val; setNZ(A); return 6;
            case 0x5F: addr=abxAddr(); val=lsr(read(addr)); write(addr,val); A^=val; setNZ(A); return 7;
            case 0x5B: addr=abyAddr(); val=lsr(read(addr)); write(addr,val); A^=val; setNZ(A); return 7;
            case 0x43: addr=izxAddr(); val=lsr(read(addr)); write(addr,val); A^=val; setNZ(A); return 8;
            case 0x53: addr=izyAddr(); val=lsr(read(addr)); write(addr,val); A^=val; setNZ(A); return 8;

            // ---- Unofficial: RRA (ROR then ADC) -----------------------------
            case 0x67: addr=zpAddr();  val=ror(read(addr)); write(addr,val); adc(val); return 5;
            case 0x77: addr=zpxAddr(); val=ror(read(addr)); write(addr,val); adc(val); return 6;
            case 0x6F: addr=absAddr(); val=ror(read(addr)); write(addr,val); adc(val); return 6;
            case 0x7F: addr=abxAddr(); val=ror(read(addr)); write(addr,val); adc(val); return 7;
            case 0x7B: addr=abyAddr(); val=ror(read(addr)); write(addr,val); adc(val); return 7;
            case 0x63: addr=izxAddr(); val=ror(read(addr)); write(addr,val); adc(val); return 8;
            case 0x73: addr=izyAddr(); val=ror(read(addr)); write(addr,val); adc(val); return 8;

            // ---- Everything else: treat as KIL (halt) ----------------------
            default:
                halted = true;
                PC = (PC - 1) & 0xFFFF; // point back at the bad opcode
                System.err.printf("Halted: unknown opcode $%02X at $%04X%n", opcode, PC);
                return 0;
        }
    }

    // -----------------------------------------------------------------------
    // Debug / dump
    // -----------------------------------------------------------------------
    @Override
    public String toString() {
        return String.format(
            "PC:%04X  A:%02X X:%02X Y:%02X  SP:%02X  P:[%s%s%s%s%s%s%s]",
            PC, A, X, Y, SP,
            N?"N":".", V?"V":".", D?"D":".", I?"I":".", Z?"Z":".", C?"C":".",
            B?"B":"."
        );
    }

    // -----------------------------------------------------------------------
    // Demo main — runs a tiny Fibonacci program
    // -----------------------------------------------------------------------
    public static void main(String[] args) {
        MOS6502 cpu = new MOS6502(0x10000);

        /*
         * Tiny 6502 program at $0600: compute Fibonacci numbers into zero page.
         *
         *   LDA #$01     ; fib(0) = 1
         *   STA $00      ; mem[0] = 1
         *   LDA #$01     ; fib(1) = 1
         *   STA $01      ; mem[1] = 1
         *   LDX #$02     ; index = 2
         * loop:
         *   LDA $00,X    ; A = fib[x] ... wait, we use a simpler rolling scheme:
         *
         * (Simpler rolling 2-variable scheme stored in $00 / $01)
         *
         *   LDX #10      ; loop 10 times
         *   LDA #$00     ; a = 0
         *   STA $00
         *   LDA #$01     ; b = 1
         *   STA $01
         * loop:
         *   LDA $01      ; tmp = b
         *   STA $02
         *   LDA $00      ; b = a + b
         *   ADC $01
         *   STA $01
         *   LDA $02      ; a = tmp
         *   STA $00
         *   DEX
         *   BNE loop
         *   BRK
         */
        int[] prog = {
    0xA2, 0x0A,        // LDX #10
    0xA9, 0x00,        // LDA #0
    0x85, 0x00,        // STA $00
    0xA9, 0x01,        // LDA #1
    0x85, 0x01,        // STA $01
    // loop:
    0xA5, 0x01,        // LDA $01
    0x85, 0x02,        // STA $02   (tmp = b)
    0x18,              // CLC       <-- moved inside loop
    0x65, 0x00,        // ADC $00
    0x85, 0x01,        // STA $01   (b = a+b)
    0xA5, 0x02,        // LDA $02
    0x85, 0x00,        // STA $00   (a = old b)
    0xCA,              // DEX
    0xD0, 0xF3,        // BNE loop
    0x00               // BRK
};
        cpu.loadProgram(0x0600, prog);
        cpu.reset(0x0600);

        System.out.println("Running Fibonacci demo (10 iterations)...");
        int cycles = 0;
        while (!cpu.isHalted()) {
            cycles += cpu.step();
        }

        System.out.printf("Done in %d cycles.%n", cycles);
        System.out.println("CPU state: " + cpu);
        System.out.printf("Result in ZP $00 = %d (fib[10] = %d expected)%n",
                          cpu.read(0x00), 55);
        System.out.println();

        // Print first few zero-page bytes for inspection
        System.out.print("Zero page $00-$0F: ");
        for (int i = 0; i < 16; i++)
            System.out.printf("%02X ", cpu.read(i));
        System.out.println();
    }
}
