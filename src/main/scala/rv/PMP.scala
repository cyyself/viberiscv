package rv

import chisel3._
import chisel3.util._

/**
 * Physical memory protection, machine mode only, 4-byte granularity (G = 0).
 *
 * pmpcfg byte layout: L[7] 0[6:5] A[4:3] X[2] W[1] R[0]
 * pmpaddr holds physical address bits [33:2].
 *
 * Because the hart only runs in M-mode, an entry restricts accesses only when it
 * is locked (L = 1); unlocked entries match but always allow M-mode accesses.
 */
object PMP {
  val NumEntries = 16

  val A_OFF   = 0.U(2.W)
  val A_TOR   = 1.U(2.W)
  val A_NA4   = 2.U(2.W)
  val A_NAPOT = 3.U(2.W)

  def locked(cfg: UInt): Bool = cfg(7)
  def mode(cfg: UInt): UInt   = cfg(4, 3)

  /** Legalize a value written to one pmpcfg byte: reserved bits read as 0, W requires R. */
  def legalizeCfg(v: UInt): UInt = {
    val r = v(0)
    Cat(v(7), 0.U(2.W), v(4, 3), v(2), v(1) && r, r)
  }
}

class PMPCheckReq extends Bundle {
  val addr = UInt(32.W)
  val r    = Bool()
  val w    = Bool()
  val x    = Bool()
}

/**
 * Combinational PMP lookup. The lowest-numbered matching entry decides; an access
 * fails only if that entry is locked and lacks the requested permission.
 * All accesses are naturally aligned and at most 4 bytes, so with 4-byte granularity
 * an access always lies entirely inside or outside each region.
 */
class PMPChecker extends Module {
  val io = IO(new Bundle {
    val cfg   = Input(Vec(PMP.NumEntries, UInt(8.W)))
    val addr  = Input(Vec(PMP.NumEntries, UInt(32.W)))
    val req   = Input(new PMPCheckReq)
    val fault = Output(Bool())
  })

  val wa = io.req.addr(31, 2).pad(32) // word address, same units as pmpaddr

  val matches = (0 until PMP.NumEntries).map { i =>
    val cfg  = io.cfg(i)
    val pa   = io.addr(i)
    val lo   = if (i == 0) 0.U(32.W) else io.addr(i - 1)
    val tor  = wa >= lo && wa < pa
    val na4  = wa === pa
    // NAPOT: trailing ones of pmpaddr encode the size; ignore those bits and one more.
    val mask = pa ^ (pa + 1.U)
    val napot = ((wa ^ pa) & ~mask) === 0.U
    MuxLookup(PMP.mode(cfg), false.B)(Seq(PMP.A_TOR -> tor, PMP.A_NA4 -> na4, PMP.A_NAPOT -> napot))
  }

  val anyMatch = matches.reduce(_ || _)
  val cfg      = PriorityMux(matches, io.cfg)
  val allowed  = (!io.req.r || cfg(0)) && (!io.req.w || cfg(1)) && (!io.req.x || cfg(2))

  io.fault := anyMatch && PMP.locked(cfg) && !allowed
}
