package rv

import chisel3._
import chisel3.util._
import Consts._

object CSRAddr {
  val mstatus   = 0x300
  val misa      = 0x301
  val mie       = 0x304
  val mtvec     = 0x305
  val mscratch  = 0x340
  val mepc      = 0x341
  val mcause    = 0x342
  val mtval     = 0x343
  val mip       = 0x344
  val mcycle    = 0xb00
  val minstret  = 0xb02
  val mcycleh   = 0xb80
  val minstreth = 0xb82
  val cycle     = 0xc00
  val instret   = 0xc02
  val cycleh    = 0xc80
  val instreth  = 0xc82
  val mvendorid = 0xf11
  val marchid   = 0xf12
  val mimpid    = 0xf13
  val mhartid   = 0xf14
  val pmpcfg0   = 0x3a0
  val pmpaddr0  = 0x3b0

  def isPMP(addr: UInt): Bool = addr(11, 5) === (pmpcfg0 >> 5).U // 0x3a0 - 0x3bf
}

/** Machine-mode-only CSR file (Zicsr subset needed to run bare-metal code and riscv-tests). */
class CSRFile extends Module {
  val io = IO(new Bundle {
    // CSR instruction access (EX stage)
    val valid   = Input(Bool())      // perform the access this cycle
    val cmd     = Input(UInt(2.W))
    val write   = Input(Bool())      // instruction actually writes (csrrw, or rs1/uimm != 0)
    val addr    = Input(UInt(12.W))
    val wdata   = Input(UInt(32.W))
    val rdata   = Output(UInt(32.W))
    val illegal = Output(Bool())
    // traps
    val trap    = Input(Bool())
    val cause   = Input(UInt(32.W))
    val epc     = Input(UInt(32.W))
    val tval    = Input(UInt(32.W))
    val mret    = Input(Bool())
    val retire  = Input(Bool())
    val mtvec   = Output(UInt(32.W))
    val mepc    = Output(UInt(32.W))
    val pmpCfg  = Output(Vec(PMP.NumEntries, UInt(8.W)))
    val pmpAddr = Output(Vec(PMP.NumEntries, UInt(32.W)))
  })

  val mie_bit  = RegInit(false.B)
  val mpie_bit = RegInit(false.B)
  val mie      = RegInit(0.U(32.W))
  val mtvec    = RegInit(0.U(32.W))
  val mscratch = RegInit(0.U(32.W))
  val mepc     = RegInit(0.U(32.W))
  val mcause   = RegInit(0.U(32.W))
  val mtval    = RegInit(0.U(32.W))
  val mcycle   = RegInit(0.U(64.W))
  val minstret = RegInit(0.U(64.W))
  val pmpCfg   = RegInit(VecInit(Seq.fill(PMP.NumEntries)(0.U(8.W))))
  val pmpAddr  = RegInit(VecInit(Seq.fill(PMP.NumEntries)(0.U(32.W))))

  val mstatus = Cat(0.U(19.W), 3.U(2.W) /* MPP = M */, 0.U(3.W), mpie_bit, 0.U(3.W), mie_bit, 0.U(3.W))
  val misa    = (1L << 30 | 1L << ('I' - 'A') | 1L << ('B' - 'A')).U(32.W) // B = Zba+Zbb+Zbs

  val readMap: Seq[(Int, UInt)] = Seq(
    CSRAddr.mstatus -> mstatus,
    CSRAddr.misa    -> misa,
    CSRAddr.mie     -> mie,
    CSRAddr.mtvec   -> mtvec,
    CSRAddr.mscratch-> mscratch,
    CSRAddr.mepc    -> mepc,
    CSRAddr.mcause  -> mcause,
    CSRAddr.mtval   -> mtval,
    CSRAddr.mip     -> 0.U(32.W),
    CSRAddr.mcycle  -> mcycle(31, 0),
    CSRAddr.mcycleh -> mcycle(63, 32),
    CSRAddr.minstret  -> minstret(31, 0),
    CSRAddr.minstreth -> minstret(63, 32),
    CSRAddr.cycle     -> mcycle(31, 0),
    CSRAddr.cycleh    -> mcycle(63, 32),
    CSRAddr.instret   -> minstret(31, 0),
    CSRAddr.instreth  -> minstret(63, 32),
    CSRAddr.mvendorid -> 0.U(32.W),
    CSRAddr.marchid   -> 0.U(32.W),
    CSRAddr.mimpid    -> 0.U(32.W),
    CSRAddr.mhartid   -> 0.U(32.W),
  ) ++ (0 until PMP.NumEntries / 4).map { n =>
    (CSRAddr.pmpcfg0 + n) -> Cat((0 until 4).reverse.map(j => pmpCfg(4 * n + j)))
  } ++ (0 until PMP.NumEntries).map { i =>
    (CSRAddr.pmpaddr0 + i) -> pmpAddr(i)
  }

  val hit = readMap.map { case (a, _) => io.addr === a.U }
  val exists = hit.reduce(_ || _)
  io.rdata := Mux1H(hit, readMap.map(_._2))
  val readOnly = io.addr(11, 10) === 3.U
  io.illegal := !exists || (io.write && readOnly)

  val nv = MuxLookup(io.cmd, io.wdata)(Seq(
    CSR_W -> io.wdata,
    CSR_S -> (io.rdata | io.wdata),
    CSR_C -> (io.rdata & ~io.wdata)
  ))

  mcycle := mcycle + 1.U
  when(io.retire) { minstret := minstret + 1.U }

  val doWrite = io.valid && io.write && !io.illegal
  when(doWrite) {
    switch(io.addr) {
      is(CSRAddr.mstatus.U)   { mie_bit := nv(3); mpie_bit := nv(7) }
      is(CSRAddr.mie.U)       { mie := nv & 0x888.U }
      is(CSRAddr.mtvec.U)     { mtvec := Cat(nv(31, 2), 0.U(2.W)) }
      is(CSRAddr.mscratch.U)  { mscratch := nv }
      is(CSRAddr.mepc.U)      { mepc := Cat(nv(31, 2), 0.U(2.W)) }
      is(CSRAddr.mcause.U)    { mcause := nv }
      is(CSRAddr.mtval.U)     { mtval := nv }
      is(CSRAddr.mcycle.U)    { mcycle := Cat(mcycle(63, 32), nv) }
      is(CSRAddr.mcycleh.U)   { mcycle := Cat(nv, mcycle(31, 0)) }
      is(CSRAddr.minstret.U)  { minstret := Cat(minstret(63, 32), nv) }
      is(CSRAddr.minstreth.U) { minstret := Cat(nv, minstret(31, 0)) }
    }
    // Locked entries ignore writes; so does the address below a locked TOR entry.
    for (n <- 0 until PMP.NumEntries / 4; j <- 0 until 4) {
      val i = 4 * n + j
      when(io.addr === (CSRAddr.pmpcfg0 + n).U && !PMP.locked(pmpCfg(i))) {
        pmpCfg(i) := PMP.legalizeCfg(nv(8 * j + 7, 8 * j))
      }
    }
    for (i <- 0 until PMP.NumEntries) {
      val lockedByNext =
        if (i + 1 < PMP.NumEntries) PMP.locked(pmpCfg(i + 1)) && PMP.mode(pmpCfg(i + 1)) === PMP.A_TOR
        else false.B
      when(io.addr === (CSRAddr.pmpaddr0 + i).U && !PMP.locked(pmpCfg(i)) && !lockedByNext) {
        pmpAddr(i) := nv
      }
    }
  }

  when(io.trap) {
    mepc     := io.epc
    mcause   := io.cause
    mtval    := io.tval
    mpie_bit := mie_bit
    mie_bit  := false.B
  }.elsewhen(io.mret) {
    mie_bit  := mpie_bit
    mpie_bit := true.B
  }

  io.mtvec   := mtvec
  io.mepc    := mepc
  io.pmpCfg  := pmpCfg
  io.pmpAddr := pmpAddr
}
