package rv

import chisel3._
import chisel3.util._
import Consts._

class FetchPacket extends Bundle {
  val pc    = UInt(32.W)
  val inst  = UInt(32.W)
  val fault = Bool() // PMP denied execution at pc; inst is not valid
}

/**
 * IF stage. Fetches aligned 64-bit beats (two instructions) into a one-line
 * fetch buffer and delivers instructions sequentially (predict fall-through).
 * A redirect from EX discards any in-flight fetch; fence.i invalidates the line.
 * If PMP denies execution at pc, no bus request is made and a fault packet is
 * delivered instead.
 */
class Fetch extends Module {
  val io = IO(new Bundle {
    val imem      = new MemPort
    val redirect  = Flipped(Valid(UInt(32.W)))
    val flushLine = Input(Bool())
    val pc        = Output(UInt(32.W))
    val pcFault   = Input(Bool()) // PMP check result for io.pc
    val out       = Decoupled(new FetchPacket)
  })

  val pc        = RegInit(RESET_PC.U(32.W)) // next instruction to deliver
  val inflight  = RegInit(false.B)
  val drop      = RegInit(false.B)          // in-flight response belongs to a squashed path
  val lineValid = RegInit(false.B)
  val lineTag   = Reg(UInt(29.W))
  val lineData  = Reg(UInt(64.W))

  val tag      = pc(31, 3)
  val hit      = lineValid && lineTag === tag
  // pc only changes during a fetch via redirect, which sets `drop`; so a good response is for `pc`
  val respGood = io.imem.resp.valid && !drop
  val data     = Mux(hit, lineData, io.imem.resp.bits)

  io.pc := pc

  io.out.valid      := hit || respGood || (io.pcFault && !inflight)
  io.out.bits.pc    := pc
  io.out.bits.inst  := Mux(pc(2), data(63, 32), data(31, 0))
  io.out.bits.fault := io.pcFault

  io.imem.req.valid      := !hit && !inflight && !io.pcFault
  io.imem.req.bits.addr  := Cat(tag, 0.U(3.W))
  io.imem.req.bits.wen   := false.B
  io.imem.req.bits.wdata := 0.U
  io.imem.req.bits.wstrb := 0.U
  io.imem.req.bits.size  := 3.U

  when(io.imem.req.fire) { inflight := true.B }
  when(io.imem.resp.valid) {
    inflight := false.B
    drop     := false.B
  }
  when(respGood) {
    lineValid := true.B
    lineTag   := tag
    lineData  := io.imem.resp.bits
  }
  when(io.out.fire) { pc := pc + 4.U }

  when(io.redirect.valid) {
    pc   := io.redirect.bits
    drop := (inflight && !io.imem.resp.valid) || io.imem.req.fire
  }
  when(io.flushLine) { lineValid := false.B }
}
