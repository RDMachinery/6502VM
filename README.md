# MOS6502

A cycle-accurate MOS Technology 6502 virtual machine implemented as a **single Java class file** — no dependencies, no build system required.

The 6502 powered the Apple II, Commodore 64, NES, Atari 2600, and BBC Micro. This VM faithfully reproduces its behaviour, including its quirks, making it suitable as the CPU core for emulators, retro tooling, or educational projects.

---

## Features

- **All 56 official opcodes** across all 13 addressing modes
- **BCD (Binary Coded Decimal)** arithmetic mode for ADC and SBC
- **Correct flag behaviour** for all instructions (N, V, B, D, I, Z, C)
- **Cycle-accurate** — `step()` returns the cycle count for each instruction
- **Hardware vectors** — RESET (`$FFFC`), NMI (`$FFFA`), IRQ/BRK (`$FFFE`)
- **JMP indirect page-wrap bug** faithfully reproduced for hardware accuracy
- **Unofficial/undocumented opcodes**: LAX, SAX, DCP, ISB, SLO, RLA, SRE, RRA
- **Memory-mapped I/O** via overridable `read()`/`write()` methods
- Zero external dependencies — drop in a single `.java` file and go

---

## Requirements

- Java 8 or later

---

## Quick Start

```bash
javac MOS6502.java
java MOS6502
```

Running `main()` executes a built-in Fibonacci demo and prints the CPU state.

---

## Usage

### Basic setup

```java
MOS6502 cpu = new MOS6502(0x10000); // 64KB address space

// Load a program at address $0600
cpu.loadProgram(0x0600,
    0xA9, 0x42,   // LDA #$42
    0x85, 0x00,   // STA $00
    0x00          // BRK
);

// Set the program counter and run
cpu.reset(0x0600);
while (!cpu.isHalted()) {
    cpu.step();
}

System.out.println(cpu);          // print CPU state
System.out.printf("%02X%n", cpu.read(0x00)); // read result from zero page
```

### Using hardware reset vectors

Write your start address to the reset vector and call `reset()` with no arguments:

```java
cpu.write(0xFFFC, 0x00);   // low byte
cpu.write(0xFFFD, 0x06);   // high byte  →  PC = $0600
cpu.reset();
```

### Stepping and cycle counting

`step()` executes one instruction and returns its cycle count:

```java
long totalCycles = 0;
while (!cpu.isHalted()) {
    totalCycles += cpu.step();
}
System.out.println("Total cycles: " + totalCycles);
```

### Triggering interrupts

```java
cpu.nmi();   // Non-maskable interrupt
cpu.irq();   // Maskable interrupt (ignored if the I flag is set)
```

---

## Memory-Mapped I/O

Override `read()` and `write()` to intercept any address range. This is the standard extension point for attaching peripherals, ROM, or bank-switching logic.

```java
MOS6502 cpu = new MOS6502(0x10000) {

    @Override
    protected int read(int addr) {
        if (addr == 0xD010) return keyboard.read(); // e.g. Apple I keyboard data
        return super.read(addr);
    }

    @Override
    protected void write(int addr, int val) {
        if (addr == 0xD012) display.write(val);     // e.g. Apple I display
        else super.write(addr, val);
    }
};
```

---

## Registers

All registers are public `int` fields and can be read or set directly:

| Field | Width  | Description                          |
|-------|--------|--------------------------------------|
| `A`   | 8-bit  | Accumulator                          |
| `X`   | 8-bit  | Index register X                     |
| `Y`   | 8-bit  | Index register Y                     |
| `SP`  | 8-bit  | Stack pointer (hardware page `$01xx`)|
| `PC`  | 16-bit | Program counter                      |

Status flags (`N`, `V`, `B`, `D`, `I`, `Z`, `C`) are public `boolean` fields.

---

## Addressing Modes

| Mode                | Example syntax   | Description                              |
|---------------------|------------------|------------------------------------------|
| Immediate           | `LDA #$10`       | Operand is the literal value             |
| Zero Page           | `LDA $10`        | Address in the first 256 bytes           |
| Zero Page, X        | `LDA $10,X`      | Zero page + X register                   |
| Zero Page, Y        | `LDA $10,Y`      | Zero page + Y register                   |
| Absolute            | `LDA $1234`      | Full 16-bit address                      |
| Absolute, X         | `LDA $1234,X`    | 16-bit address + X                       |
| Absolute, Y         | `LDA $1234,Y`    | 16-bit address + Y                       |
| Indirect            | `JMP ($1234)`    | JMP only; address read from pointer      |
| Indexed Indirect    | `LDA ($10,X)`    | Zero page pointer + X, then dereference  |
| Indirect Indexed    | `LDA ($10),Y`    | Dereference zero page pointer, then + Y  |
| Accumulator         | `ASL A`          | Operates directly on A                   |
| Implied             | `CLC`            | No operand                               |
| Relative            | `BNE label`      | Signed 8-bit offset for branch instructions |

---

## Unofficial Opcodes

The following undocumented opcodes are implemented, covering the most commonly used ones found in NES and C64 software:

| Mnemonic | Operation                        |
|----------|----------------------------------|
| LAX      | Load A and X from memory         |
| SAX      | Store A & X to memory            |
| DCP      | DEC memory, then CMP with A      |
| ISB/ISC  | INC memory, then SBC from A      |
| SLO      | ASL memory, then ORA into A      |
| RLA      | ROL memory, then AND into A      |
| SRE      | LSR memory, then EOR into A      |
| RRA      | ROR memory, then ADC into A      |

Unknown opcodes halt the CPU (setting `isHalted()` to `true`) and print a diagnostic to `stderr`.

---

## Halting

The CPU halts on:
- A `BRK` instruction (after jumping through the IRQ vector)
- An unrecognised opcode

Check `cpu.isHalted()` in your run loop. You can inspect the CPU state after halting — `PC` points to the offending opcode for unrecognised instructions.

---

## CPU State Dump

`toString()` produces a compact one-line summary:

```
PC:0615  A:37 X:00 Y:00  SP:FF  P:[N.D.I.C.]
```

---

## Known Limitations

- **No decimal-mode flag effects on N/V** — BCD arithmetic adjusts the result byte but the N and V flag behaviour in decimal mode is not fully cycle-exact to the NMOS 6502 (this only matters for a handful of edge cases rarely relied upon by real software).
- **No cycle-stealing** for page-crossing branches and indexed addressing — cycle counts are the base values; the +1 penalty for crossing a page boundary is not applied.
- **CMOS variants not supported** — this targets the original NMOS 6502. The 65C02 adds instructions (`STZ`, `TRB`, `TSB`, etc.) that are not implemented.
- **No built-in disassembler** — you will need to supply your own if you want instruction traces.

---

## Extending the VM

`MOS6502` is designed to be subclassed. Common extension points:

- **`read(int addr)` / `write(int addr, int val)`** — memory bus; attach ROM, RAM, and peripherals
- **`step()`** — can be called on a timer or driven by a host clock to achieve real-time emulation speed
- Register fields (`A`, `X`, `Y`, `SP`, `PC`) and flag fields (`N`, `V`, ...) are all `public` for easy inspection by debuggers and test harnesses

---

## License

This project is released into the public domain. Do whatever you like with it.
