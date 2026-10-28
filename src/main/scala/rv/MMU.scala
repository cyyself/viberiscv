package rv

import chisel3._
import chisel3.util._
import Consts._

/** A cached Sv32 leaf PTE. Only entries with A = 1 are cached (Svade). */
class TLBEntry extends Bundle {
  val vpn       = UInt(20.W)
  val ppn       = UInt(22.W)
  val superpage = Bool() // 4 MiB megapage: only vpn[19:10] is compared
  val u         = Bool()
  val x         = Bool()
  val w         = Bool()
  val r         = Bool()
  val d         = Bool()

  /** Physical address (34 bits) of virtual address `va` within this page. */
  def paddr(va: UInt): UInt =
    Mux(superpage, Cat(ppn(21, 10), va(21, 0)), Cat(ppn, va(11, 0)))
}

object MMU {
  val ACC_FETCH = 0.U(2.W)
  val ACC_LOAD  = 1.U(2.W)
  val ACC_STORE = 2.U(2.W)

  /**
   * Page permission check for a TLB hit. Stores to pages with D = 0 fault so software
   * can set D (Svade); SUM lets S-mode load/store U pages but never execute them.
   */
  def allowed(e: TLBEntry, acc: UInt, priv: UInt, sum: Bool, mxr: Bool): Bool = {
    val userOk = Mux(priv === PRV_U, e.u, !e.u || (sum && acc =/= ACC_FETCH))
    val permOk = MuxLookup(acc, false.B)(Seq(
      ACC_FETCH -> e.x,
      ACC_LOAD  -> (e.r || (mxr && e.x)),
      ACC_STORE -> (e.w && e.d)
    ))
    userOk && permOk
  }

  def pageFaultCause(acc: UInt): UInt =
    MuxLookup(acc, CAUSE_LOAD_PAGE.U)(Seq(ACC_FETCH -> CAUSE_FETCH_PAGE.U, ACC_STORE -> CAUSE_STORE_PAGE.U))

  def accessFaultCause(acc: UInt): UInt =
    MuxLookup(acc, CAUSE_LOAD_ACCESS.U)(Seq(ACC_FETCH -> CAUSE_FETCH_ACCESS.U, ACC_STORE -> CAUSE_STORE_ACCESS.U))
}

/** Small fully-associative TLB with round-robin replacement and no ASIDs. */
class TLB(entries: Int) extends Module {
  val io = IO(new Bundle {
    val vpn    = Input(UInt(20.W))
    val hit    = Output(Bool())
    val entry  = Output(new TLBEntry)
    val insert = Flipped(Valid(new TLBEntry))
    val flush  = Input(Bool())
  })

  val valid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val tlb   = Reg(Vec(entries, new TLBEntry))
  val next  = RegInit(0.U(log2Ceil(entries).W))

  val hits = (0 until entries).map { i =>
    val e = tlb(i)
    valid(i) && Mux(e.superpage, e.vpn(19, 10) === io.vpn(19, 10), e.vpn === io.vpn)
  }
  io.hit   := hits.reduce(_ || _)
  io.entry := Mux1H(hits, tlb)

  when(io.insert.valid) {
    tlb(next)   := io.insert.bits
    valid(next) := true.B
    next        := next + 1.U
  }
  when(io.flush) { valid.foreach(_ := false.B) }
}

class PTWResp extends Bundle {
  val isData = Bool()
  val fault  = Bool()
  val cause  = UInt(32.W) // page fault or access fault for the original access type
  val entry  = new TLBEntry
}

/**
 * Sv32 hardware page-table walker shared by the ITLB and DTLB (data requests first).
 * PTE reads are checked against PMP as S-mode reads. A/D bits are never updated:
 * a leaf with A = 0 raises a page fault (Svade), as does a misaligned superpage.
 */
class PTW extends Module {
  val io = IO(new Bundle {
    val dReq    = Flipped(Decoupled(new Bundle { val vpn = UInt(20.W); val store = Bool() }))
    val iReq    = Flipped(Decoupled(UInt(20.W)))
    val resp    = Valid(new PTWResp)
    val killI   = Input(Bool()) // drop the result of an instruction-side walk
    val flush   = Input(Bool()) // sfence.vma / satp write: drop any result
    val satpPpn = Input(UInt(22.W))
    val pmpCfg  = Input(Vec(PMP.NumEntries, UInt(8.W)))
    val pmpAddr = Input(Vec(PMP.NumEntries, UInt(32.W)))
    val mem     = new MemPort
  })

  val sIdle :: sCheck :: sWait :: sDone :: Nil = Enum(4)
  val state  = RegInit(sIdle)
  val vpn    = Reg(UInt(20.W))
  val isData = Reg(Bool())
  val store  = Reg(Bool())
  val level  = Reg(UInt(1.W))
  val ptAddr = Reg(UInt(34.W))
  val killed = Reg(Bool())
  val result = Reg(new PTWResp)

  val acc = Mux(isData, Mux(store, MMU.ACC_STORE, MMU.ACC_LOAD), MMU.ACC_FETCH)
  def fail(cause: UInt): Unit = {
    result.fault := true.B
    result.cause := cause
    state        := sDone
  }

  io.dReq.ready := state === sIdle
  io.iReq.ready := state === sIdle && !io.dReq.valid
  when(io.dReq.fire || io.iReq.fire) {
    val v = Mux(io.dReq.valid, io.dReq.bits.vpn, io.iReq.bits)
    vpn    := v
    isData := io.dReq.valid
    store  := io.dReq.valid && io.dReq.bits.store
    level  := 1.U
    ptAddr := Cat(io.satpPpn, v(19, 10), 0.U(2.W))
    killed := false.B
    state  := sCheck
  }

  val pmp = Module(new PMPChecker)
  pmp.io.cfg      := io.pmpCfg
  pmp.io.addr     := io.pmpAddr
  pmp.io.req.addr := ptAddr
  pmp.io.req.priv := PRV_S
  pmp.io.req.r    := true.B
  pmp.io.req.w    := false.B
  pmp.io.req.x    := false.B
  val ptOk = ptAddr(33, 32) === 0.U && !pmp.io.fault

  io.mem.req.valid      := state === sCheck && ptOk
  io.mem.req.bits.addr  := ptAddr(31, 0)
  io.mem.req.bits.wen   := false.B
  io.mem.req.bits.wdata := 0.U
  io.mem.req.bits.wstrb := 0.U
  io.mem.req.bits.size  := 2.U

  when(state === sCheck) {
    when(!ptOk) { fail(MMU.accessFaultCause(acc)) }
      .elsewhen(io.mem.req.fire) { state := sWait }
  }

  val pte = Mux(ptAddr(2), io.mem.resp.bits(63, 32), io.mem.resp.bits(31, 0))
  val v   = pte(0)
  val r   = pte(1)
  val w   = pte(2)
  val x   = pte(3)
  val ppn = pte(31, 10)
  when(state === sWait && io.mem.resp.valid) {
    when(!v || (!r && w)) {
      fail(MMU.pageFaultCause(acc))
    }.elsewhen(r || x) {
      // Leaf
      when((level === 1.U && ppn(9, 0) =/= 0.U) || !pte(6) /* A */) {
        fail(MMU.pageFaultCause(acc))
      }.otherwise {
        result.fault           := false.B
        result.entry.vpn       := vpn
        result.entry.ppn       := ppn
        result.entry.superpage := level === 1.U
        result.entry.u         := pte(4)
        result.entry.x         := x
        result.entry.w         := w
        result.entry.r         := r
        result.entry.d         := pte(7)
        state                  := sDone
      }
    }.elsewhen(level === 0.U) {
      fail(MMU.pageFaultCause(acc))
    }.otherwise {
      level  := 0.U
      ptAddr := Cat(ppn, vpn(9, 0), 0.U(2.W))
      state  := sCheck
    }
  }

  // A flush also cancels a walk accepted this cycle, which would read the old satp
  when(io.flush || (state =/= sIdle && io.killI && !isData)) { killed := true.B }

  io.resp.valid       := state === sDone && !killed && !io.flush && !(io.killI && !isData)
  io.resp.bits        := result
  io.resp.bits.isData := isData
  when(state === sDone) { state := sIdle }
}
