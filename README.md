# viberiscv

An RV32I + Zba/Zbb/Zbs processor in Chisel 7 (no rocket-chip), with M/S/U privilege modes and
Sv32 virtual memory. It has a classic 5-stage pipeline and one 64-bit AXI4 master port. A Verilator
testbench models the bus with a boot ROM, DRAM and a UART.

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

- **IF** (`Fetch.scala`): reads aligned 64-bit beats into a one-line fetch buffer tagged by
  physical address, so one bus read serves two instructions. It predicts fall-through.
  `fence.i` invalidates the line. The fetch PC is translated by the ITLB and checked by PMP
  before any bus request; a fault becomes a fault packet that traps in EX.
- **ID** (`Decode.scala`): table decoder and immediate generator. The register file is read
  with WB->ID bypass. If an instruction uses the result of a load in EX, it waits one cycle.
- **EX** (`Core.scala`): ALU (base plus all Zba/Zbb/Zbs operations, single cycle) and
  branch/jump resolution (2 squashed instructions on a taken branch). Operands are forwarded
  from EX/MEM and MEM/WB. CSR access, `mret`/`sret` and traps (`ecall`, `ebreak`, illegal
  instruction, misaligned jump target, fetch faults) are handled here. `fence.i`,
  `sfence.vma` and writes to `satp` or PMP CSRs refetch the younger instructions.
- **MEM**: one data access at a time on the bus. The virtual address is translated by the
  DTLB using the effective privilege (MPRV), then checked by PMP. Misaligned loads and stores
  are done in hardware: inside one aligned 8-byte beat they take a single bus transfer with
  byte strobes; across a beat boundary they take two, each translated and PMP-checked on its
  own (so they may cross pages). A fault on the second beat reports that beat's address, and
  the first beat of a store may already be written. Page faults and access faults trap from
  MEM and squash the instruction in EX. While MEM waits, earlier stages stall; EX keeps
  capturing forwarded operands meanwhile.
- **WB**: register-file write and the commit trace port (`commit_*` signals at the top level).
- **Privilege / CSRs** (`CSR.scala`): M, S and U modes. mstatus (SIE/MIE/SPIE/MPIE/SPP/MPP,
  MPRV, SUM, MXR, TVM, TW, TSR), sstatus, misa (I, B, S, U), medeleg/mideleg, mie/sie,
  mip/sip, m/stvec (direct), m/sscratch, m/sepc, m/scause, m/stval, satp, m/scounteren,
  mcycle[h], minstret[h], cycle/instret[h], the ID registers, and zero m/senvcfg and mstatush.
  Synchronous exceptions can be delegated to S-mode. CSR privilege, read-only, counter-enable
  and TVM checks raise illegal-instruction, as do `mret` below M, `sret` in U or under TSR,
  and `sfence.vma` in U or under TVM. `wfi` completes immediately (no interrupts exist).
- **MMU** (`MMU.scala`): Sv32 with 4 KiB pages and 4 MiB megapages. Separate 4-entry, fully
  associative ITLB and DTLB (round-robin replacement, no ASIDs); `sfence.vma` and `satp`
  writes flush both. A shared hardware page-table walker serves both TLBs (data first) on its
  own bus port; its PTE reads are PMP-checked as S-mode reads. A/D bits are not updated in
  hardware: a leaf with A=0, or a store to a page with D=0, raises a page fault (Svade).
- **PMP** (`PMP.scala`): 16 entries (`pmpcfg0-3`, `pmpaddr0-15`), 4-byte granularity, OFF/TOR/
  NA4/NAPOT. S/U accesses must match an entry that grants the permission; M-mode is restricted
  only by locked (L=1) entries. Locked entries ignore writes, as does the address below a
  locked TOR entry.
- **Bus** (`Bus.scala`): `AXI4Bridge` arbitrates the data, page-table-walker and instruction
  ports (in that priority) onto one AXI4 master: 32-bit address, 64-bit data, 4-bit ID,
  single-beat INCR transfers with the narrow size encoded in AxSIZE. Ports appear in Verilog
  as `m_axi_*`.

Not supported: interrupts (no timer or external interrupt sources), ASIDs, debug triggers,
and physical addresses above 4 GiB (they raise access faults).

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
