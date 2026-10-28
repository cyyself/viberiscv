package rv

import chisel3._
import chisel3.util._
import Consts._

class FetchPacket extends Bundle {
  val pc    = UInt(32.W)
  val inst  = UInt(32.W)
  val fault = Valid(UInt(32.W)) // fetch exception cause; inst is not valid
}

/** Translation of the fetch PC, computed by the core (ITLB + PMP). */
class FetchXlate extends Bundle {
  val ready = Bool()            // paddr/fault are valid for io.pc (false while walking)
  val fault = Valid(UInt(32.W)) // page fault or access fault cause
  val paddr = UInt(32.W)
}

/**
 * IF stage. Fetches aligned 64-bit beats (two instructions) into a one-line fetch
 * buffer tagged by physical address and delivers instructions sequentially (predict
 * fall-through). A redirect from EX discards any in-flight fetch; fence.i invalidates
 * the line. If translation or PMP faults, no bus request is made and a fault packet
 * is delivered instead.
 */
class Fetch extends Module {
  val io = IO(new Bundle {
    val imem      = new MemPort
    val redirect  = Flipped(Valid(UInt(32.W)))
    val flushLine = Input(Bool())
    val pc        = Output(UInt(32.W))
    val xlate     = Input(new FetchXlate)
    val out       = Decoupled(new FetchPacket)
  })

  val pc        = RegInit(RESET_PC.U(32.W)) // next instruction to deliver
  val inflight  = RegInit(false.B)
  val drop      = RegInit(false.B)          // in-flight response belongs to a squashed path
  val reqTag    = Reg(UInt(29.W))
  val lineValid = RegInit(false.B)
  val lineTag   = Reg(UInt(29.W))
  val lineData  = Reg(UInt(64.W))

  val xl       = io.xlate
  val canFetch = xl.ready && !xl.fault.valid
  val tag      = xl.paddr(31, 3)
  val hit      = canFetch && lineValid && lineTag === tag
  val respGood = io.imem.resp.valid && !drop
  val respHit  = respGood && canFetch && reqTag === tag
  val faultOut = xl.ready && xl.fault.valid && !inflight
  val data     = Mux(hit, lineData, io.imem.resp.bits)

  io.pc := pc

  io.out.valid            := hit || respHit || faultOut
  io.out.bits.pc          := pc
  io.out.bits.inst        := Mux(pc(2), data(63, 32), data(31, 0))
  io.out.bits.fault.valid := faultOut
  io.out.bits.fault.bits  := xl.fault.bits

  io.imem.req.valid      := canFetch && !hit && !inflight
  io.imem.req.bits.addr  := Cat(tag, 0.U(3.W))
  io.imem.req.bits.wen   := false.B
  io.imem.req.bits.wdata := 0.U
  io.imem.req.bits.wstrb := 0.U
  io.imem.req.bits.size  := 3.U

  when(io.imem.req.fire) {
    inflight := true.B
    reqTag   := tag
  }
  when(io.imem.resp.valid) {
    inflight := false.B
    drop     := false.B
  }
  when(respGood) {
    lineValid := true.B
    lineTag   := reqTag
    lineData  := io.imem.resp.bits
  }
  when(io.out.fire) { pc := pc + 4.U }

  when(io.redirect.valid) {
    pc   := io.redirect.bits
    drop := (inflight && !io.imem.resp.valid) || io.imem.req.fire
  }
  when(io.flushLine) { lineValid := false.B }
}
