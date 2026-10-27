# viberiscv

An RV32I + Zba/Zbb/Zbs processor in Chisel 7 (no rocket-chip). It has a classic 5-stage
pipeline and one 64-bit AXI4 master port. A Verilator testbench models the bus with a boot ROM, DRAM and a UART.

## Quick start

```sh
make sim          # Chisel -> generated/*.sv -> build/VRVTop
make run-hello    # boots from ROM, runs sw/hello on DRAM, prints via UART
```

Requirements: mill, JDK 17, Verilator 5, and a RISC-V GCC (default `CROSS=riscv64-linux-gnu-`).

## Memory map

| Region  | Base         | Size   | Notes                                                    |
|---------|--------------|--------|----------------------------------------------------------|
| Boot ROM| `0x00010000` | 64 KiB | Reset PC. Default ROM jumps to DRAM with `a0 = mhartid`. |
| UART    | `0x60000000` | 4 KiB  | 16550 subset: THR @+0, LSR @+5 (always TX-ready)         |
| DRAM    | `0x80000000` | 64 MiB | Programs load here (ELF segments or raw `.bin`)          |

The simulation stops when the program writes `(code << 1) | 1` to the ELF symbol `tohost`
(the riscv-tests convention). Code 0 means PASS.

## Microarchitecture (`src/main/scala/rv`)

- **IF** (`Fetch.scala`): reads aligned 64-bit beats into a one-line fetch buffer, so one bus
  read serves two instructions. It predicts fall-through. `fence.i` invalidates the line.
- **ID** (`Decode.scala`): table decoder and immediate generator. The register file is read
  with WB->ID bypass. If an instruction uses the result of a load in EX, it waits one cycle.
- **EX** (`Core.scala`): ALU (base plus all Zba/Zbb/Zbs operations, single cycle),
  branch/jump resolution and the redirect (2 squashed instructions on a taken branch). Operands are forwarded from EX/MEM and MEM/WB. CSR access, traps
  (`ecall`, `ebreak`, illegal instruction) and `mret` are also handled here.
- **MEM**: one data access at a time on the bus. While it waits, all earlier stages stall.
  While EX is stalled it keeps capturing forwarded operands, so values aren't lost when their
  producer retires.
- **WB**: register-file write and the commit trace port (`commit_*` signals at the top level).
- **CSRs** (`CSR.scala`): M-mode only. mstatus, misa, mie/mip, mtvec (direct), mscratch, mepc,
  mcause, mtval, mcycle[h], minstret[h], cycle/instret[h], and the ID registers.
  Unimplemented CSRs raise illegal-instruction.
- **Bus** (`Bus.scala`): `AXI4Bridge` arbitrates the I and D ports (D has priority) onto one
  AXI4 master: 32-bit address, 64-bit data, 4-bit ID, single-beat INCR transfers with the
  narrow size encoded in AxSIZE. Ports appear in Verilog as `m_axi_*`.

Not supported: misaligned loads/stores (they neither work nor trap),
interrupts, U/S modes.

## Simulator options (`build/VRVTop`)

```
--bootrom <file>    raw binary at 0x10000 (default: built-in jump to DRAM)
--max-cycles <n>    default 50M
--dram-size <MiB>   default 64
--latency <n>       AXI slave response latency (cycles)
--tohost <addr>     override the tohost address
--vcd <file>        waveform dump
--trace             commit log on stderr (pc, inst, rd write)
```

The AXI slave model (`sim/axi_slave.h`) accepts up to 4 outstanding transactions per
direction. It supports FIXED/INCR/WRAP bursts and returns DECERR for unmapped addresses.
Devices are in `sim/devices.h`.
