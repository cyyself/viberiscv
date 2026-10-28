package rv

import chisel3._
import chisel3.util._
import Consts._

object CSRAddr {
  val sstatus    = 0x100
  val sie        = 0x104
  val stvec      = 0x105
  val scounteren = 0x106
  val senvcfg    = 0x10a
  val sscratch   = 0x140
  val sepc       = 0x141
  val scause     = 0x142
  val stval      = 0x143
  val sip        = 0x144
  val satp       = 0x180
  val mstatus    = 0x300
  val misa       = 0x301
  val medeleg    = 0x302
  val mideleg    = 0x303
  val mie        = 0x304
  val mtvec      = 0x305
  val mcounteren = 0x306
  val menvcfg    = 0x30a
  val mstatush   = 0x310
  val menvcfgh   = 0x31a
  val mscratch   = 0x340
  val mepc       = 0x341
  val mcause     = 0x342
  val mtval      = 0x343
  val mip        = 0x344
  val mcycle     = 0xb00
  val minstret   = 0xb02
  val mcycleh    = 0xb80
  val minstreth  = 0xb82
  val cycle      = 0xc00
  val instret    = 0xc02
  val cycleh     = 0xc80
  val instreth   = 0xc82
  val mvendorid  = 0xf11
  val marchid    = 0xf12
  val mimpid     = 0xf13
  val mhartid    = 0xf14
  val pmpcfg0    = 0x3a0
  val pmpaddr0   = 0x3b0

  def isPMP(addr: UInt): Bool = addr(11, 5) === (pmpcfg0 >> 5).U // 0x3a0 - 0x3bf
}

class StatusBits extends Bundle {
  val mprv = Bool()
  val mpp  = UInt(2.W)
  val sum  = Bool()
  val mxr  = Bool()
  val tvm  = Bool()
  val tw   = Bool()
  val tsr  = Bool()
}

/**
 * M/S/U-mode CSR file (Zicsr subset for bare-metal code, an Sv32 supervisor and riscv-tests).
 * No interrupt sources exist: mip/sip read as zero.
 */
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
    val trapVec = Output(UInt(32.W)) // handler address for `cause` at the current privilege
    val mret    = Input(Bool())
    val sret    = Input(Bool())
    val retire  = Input(Bool())      // an instruction left EX without an exception
    val unretire = Input(Bool())     // ...but then faulted in MEM
    // state
    val priv    = Output(UInt(2.W))
    val status  = Output(new StatusBits)
    val mepc    = Output(UInt(32.W))
    val sepc    = Output(UInt(32.W))
    val satpOn  = Output(Bool())     // satp.MODE = Sv32
    val satpPpn = Output(UInt(22.W))
    val pmpCfg  = Output(Vec(PMP.NumEntries, UInt(8.W)))
    val pmpAddr = Output(Vec(PMP.NumEntries, UInt(32.W)))
  })

  val priv = RegInit(PRV_M)

  // mstatus fields
  val sie  = RegInit(false.B)
  val mie  = RegInit(false.B)
  val spie = RegInit(false.B)
  val mpie = RegInit(false.B)
  val spp  = RegInit(false.B)
  val mpp  = RegInit(PRV_M)
  val mprv = RegInit(false.B)
  val sum  = RegInit(false.B)
  val mxr  = RegInit(false.B)
  val tvm  = RegInit(false.B)
  val tw   = RegInit(false.B)
  val tsr  = RegInit(false.B)

  val medeleg    = RegInit(0.U(32.W))
  val mideleg    = RegInit(0.U(32.W))
  val mieReg     = RegInit(0.U(32.W))
  val mtvec      = RegInit(0.U(32.W))
  val mcounteren = RegInit(0.U(32.W))
  val mscratch   = RegInit(0.U(32.W))
  val mepc       = RegInit(0.U(32.W))
  val mcause     = RegInit(0.U(32.W))
  val mtval      = RegInit(0.U(32.W))
  val stvec      = RegInit(0.U(32.W))
  val scounteren = RegInit(0.U(32.W))
  val sscratch   = RegInit(0.U(32.W))
  val sepc       = RegInit(0.U(32.W))
  val scause     = RegInit(0.U(32.W))
  val stval      = RegInit(0.U(32.W))
  val satpMode   = RegInit(false.B)
  val satpPpn    = RegInit(0.U(22.W))
  val mcycle     = RegInit(0.U(64.W))
  val minstret   = RegInit(0.U(64.W))
  val pmpCfg     = RegInit(VecInit(Seq.fill(PMP.NumEntries)(0.U(8.W))))
  val pmpAddr    = RegInit(VecInit(Seq.fill(PMP.NumEntries)(0.U(32.W))))

  val mstatus = Cat(0.U(9.W), tsr, tw, tvm, mxr, sum, mprv, 0.U(4.W), mpp, 0.U(2.W), spp,
                    mpie, 0.U(1.W), spie, 0.U(1.W), mie, 0.U(1.W), sie, 0.U(1.W))
  val sstatusMask = 0x000c0122L // SIE, SPIE, SPP, SUM, MXR
  val misa    = (1L << 30 | Seq('I', 'B', 'S', 'U').map(c => 1L << (c - 'A')).reduce(_ | _)).U(32.W)
  val satp    = Cat(satpMode, 0.U(9.W) /* no ASID bits */, satpPpn)

  // Writable bit masks
  val medelegMask = 0xb3ff.U(32.W) // synchronous exceptions 0-9, 12, 13, 15 (not ecall from M)
  val midelegMask = 0x222.U(32.W)  // SSIP, STIP, SEIP
  val mieMask     = 0xaaa.U(32.W)
  val counterMask = 0x5.U(32.W)    // CY, IR (no time or hpm counters)

  val readMap: Seq[(Int, UInt)] = Seq(
    CSRAddr.sstatus    -> (mstatus & sstatusMask.U),
    CSRAddr.sie        -> (mieReg & mideleg),
    CSRAddr.stvec      -> stvec,
    CSRAddr.scounteren -> scounteren,
    CSRAddr.senvcfg    -> 0.U(32.W),
    CSRAddr.sscratch   -> sscratch,
    CSRAddr.sepc       -> sepc,
    CSRAddr.scause     -> scause,
    CSRAddr.stval      -> stval,
    CSRAddr.sip        -> 0.U(32.W),
    CSRAddr.satp       -> satp,
    CSRAddr.mstatus    -> mstatus,
    CSRAddr.misa       -> misa,
    CSRAddr.medeleg    -> medeleg,
    CSRAddr.mideleg    -> mideleg,
    CSRAddr.mie        -> mieReg,
    CSRAddr.mtvec      -> mtvec,
    CSRAddr.mcounteren -> mcounteren,
    CSRAddr.menvcfg    -> 0.U(32.W),
    CSRAddr.mstatush   -> 0.U(32.W),
    CSRAddr.menvcfgh   -> 0.U(32.W),
    CSRAddr.mscratch   -> mscratch,
    CSRAddr.mepc       -> mepc,
    CSRAddr.mcause     -> mcause,
    CSRAddr.mtval      -> mtval,
    CSRAddr.mip        -> 0.U(32.W),
    CSRAddr.mcycle     -> mcycle(31, 0),
    CSRAddr.mcycleh    -> mcycle(63, 32),
    CSRAddr.minstret   -> minstret(31, 0),
    CSRAddr.minstreth  -> minstret(63, 32),
    CSRAddr.cycle      -> mcycle(31, 0),
    CSRAddr.cycleh     -> mcycle(63, 32),
    CSRAddr.instret    -> minstret(31, 0),
    CSRAddr.instreth   -> minstret(63, 32),
    CSRAddr.mvendorid  -> 0.U(32.W),
    CSRAddr.marchid    -> 0.U(32.W),
    CSRAddr.mimpid     -> 0.U(32.W),
    CSRAddr.mhartid    -> 0.U(32.W),
  ) ++ (0 until PMP.NumEntries / 4).map { n =>
    (CSRAddr.pmpcfg0 + n) -> Cat((0 until 4).reverse.map(j => pmpCfg(4 * n + j)))
  } ++ (0 until PMP.NumEntries).map { i =>
    (CSRAddr.pmpaddr0 + i) -> pmpAddr(i)
  }

  val hit = readMap.map { case (a, _) => io.addr === a.U }
  val exists = hit.reduce(_ || _)
  io.rdata := Mux1H(hit, readMap.map(_._2))

  // Access checks: privilege encoded in addr[9:8], read-only in addr[11:10] = 3,
  // counter enables for user-level counters, and satp under TVM.
  val readOnly  = io.addr(11, 10) === 3.U
  val privOk    = priv >= io.addr(9, 8)
  val isCounter = io.addr(11, 5) === (CSRAddr.cycle >> 5).U || io.addr(11, 5) === (CSRAddr.cycleh >> 5).U
  val cntBit    = io.addr(4, 0)
  val counterOk = !isCounter || priv === PRV_M ||
                  (mcounteren(cntBit) && (priv === PRV_S || scounteren(cntBit)))
  val satpOk    = io.addr =/= CSRAddr.satp.U || !(priv === PRV_S && tvm)
  io.illegal := !exists || !privOk || (io.write && readOnly) || !counterOk || !satpOk

  val nv = MuxLookup(io.cmd, io.wdata)(Seq(
    CSR_W -> io.wdata,
    CSR_S -> (io.rdata | io.wdata),
    CSR_C -> (io.rdata & ~io.wdata)
  ))

  // minstret counts in EX so a CSR read sees every older instruction; a write
  // (below) replaces the count and suppresses the writing instruction's increment.
  mcycle := mcycle + 1.U
  when(io.retire && !io.unretire) { minstret := minstret + 1.U }
    .elsewhen(io.unretire && !io.retire) { minstret := minstret - 1.U }

  def writeSstatus(v: UInt): Unit = {
    sie  := v(1)
    spie := v(5)
    spp  := v(8)
    sum  := v(18)
    mxr  := v(19)
  }

  val doWrite = io.valid && io.write && !io.illegal
  when(doWrite) {
    switch(io.addr) {
      is(CSRAddr.sstatus.U)    { writeSstatus(nv) }
      is(CSRAddr.sie.U)        { mieReg := (mieReg & ~mideleg) | (nv & mideleg) }
      is(CSRAddr.stvec.U)      { stvec := Cat(nv(31, 2), 0.U(2.W)) }
      is(CSRAddr.scounteren.U) { scounteren := nv & counterMask }
      is(CSRAddr.sscratch.U)   { sscratch := nv }
      is(CSRAddr.sepc.U)       { sepc := Cat(nv(31, 2), 0.U(2.W)) }
      is(CSRAddr.scause.U)     { scause := nv }
      is(CSRAddr.stval.U)      { stval := nv }
      is(CSRAddr.satp.U)       { satpMode := nv(31); satpPpn := nv(21, 0) }
      is(CSRAddr.mstatus.U) {
        writeSstatus(nv)
        mie  := nv(3)
        mpie := nv(7)
        when(nv(12, 11) =/= 2.U) { mpp := nv(12, 11) } // 2 is reserved: keep the old value
        mprv := nv(17)
        tvm  := nv(20)
        tw   := nv(21)
        tsr  := nv(22)
      }
      is(CSRAddr.medeleg.U)    { medeleg := nv & medelegMask }
      is(CSRAddr.mideleg.U)    { mideleg := nv & midelegMask }
      is(CSRAddr.mie.U)        { mieReg := nv & mieMask }
      is(CSRAddr.mtvec.U)      { mtvec := Cat(nv(31, 2), 0.U(2.W)) }
      is(CSRAddr.mcounteren.U) { mcounteren := nv & counterMask }
      is(CSRAddr.mscratch.U)   { mscratch := nv }
      is(CSRAddr.mepc.U)       { mepc := Cat(nv(31, 2), 0.U(2.W)) }
      is(CSRAddr.mcause.U)     { mcause := nv }
      is(CSRAddr.mtval.U)      { mtval := nv }
      is(CSRAddr.mcycle.U)     { mcycle := Cat(mcycle(63, 32), nv) }
      is(CSRAddr.mcycleh.U)    { mcycle := Cat(nv, mcycle(31, 0)) }
      is(CSRAddr.minstret.U)   { minstret := Cat(minstret(63, 32), nv) }
      is(CSRAddr.minstreth.U)  { minstret := Cat(nv, minstret(31, 0)) }
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

  // Exceptions taken below M-mode go to S-mode when delegated in medeleg.
  val toS = priv =/= PRV_M && medeleg(io.cause(3, 0))
  io.trapVec := Mux(toS, stvec, mtvec)

  when(io.trap) {
    when(toS) {
      sepc   := io.epc
      scause := io.cause
      stval  := io.tval
      spie   := sie
      sie    := false.B
      spp    := priv(0)
      priv   := PRV_S
    }.otherwise {
      mepc   := io.epc
      mcause := io.cause
      mtval  := io.tval
      mpie   := mie
      mie    := false.B
      mpp    := priv
      priv   := PRV_M
    }
  }.elsewhen(io.mret) {
    priv := mpp
    mie  := mpie
    mpie := true.B
    mpp  := PRV_U
    when(mpp =/= PRV_M) { mprv := false.B }
  }.elsewhen(io.sret) {
    priv := Cat(0.U(1.W), spp)
    sie  := spie
    spie := true.B
    spp  := false.B
    mprv := false.B
  }

  io.priv        := priv
  io.status.mprv := mprv
  io.status.mpp  := mpp
  io.status.sum  := sum
  io.status.mxr  := mxr
  io.status.tvm  := tvm
  io.status.tw   := tw
  io.status.tsr  := tsr
  io.mepc        := mepc
  io.sepc        := sepc
  io.satpOn      := satpMode
  io.satpPpn     := satpPpn
  io.pmpCfg      := pmpCfg
  io.pmpAddr     := pmpAddr
}
