package rv

import chisel3._
import chisel3.util._

/** Simple single-outstanding memory request used between the core and the AXI bridge. */
class MemReq extends Bundle {
  val addr  = UInt(32.W)
  val wen   = Bool()
  val wdata = UInt(32.W)  // already shifted to its byte lanes within the 32-bit word
  val wstrb = UInt(4.W)
  val size  = UInt(2.W)   // log2(bytes): 0..3
}

class MemPort extends Bundle {
  val req  = Decoupled(new MemReq)
  val resp = Flipped(Valid(UInt(64.W))) // aligned 64-bit beat containing addr; also pulses for writes
}

/** AXI4 master interface, flattened so Verilog ports come out as m_axi_awvalid etc. */
class AXI4Master(val addrBits: Int = 32, val dataBits: Int = 64, val idBits: Int = 4) extends Bundle {
  val awvalid = Output(Bool())
  val awready = Input(Bool())
  val awid    = Output(UInt(idBits.W))
  val awaddr  = Output(UInt(addrBits.W))
  val awlen   = Output(UInt(8.W))
  val awsize  = Output(UInt(3.W))
  val awburst = Output(UInt(2.W))
  val awlock  = Output(Bool())
  val awcache = Output(UInt(4.W))
  val awprot  = Output(UInt(3.W))
  val awqos   = Output(UInt(4.W))

  val wvalid  = Output(Bool())
  val wready  = Input(Bool())
  val wdata   = Output(UInt(dataBits.W))
  val wstrb   = Output(UInt((dataBits / 8).W))
  val wlast   = Output(Bool())

  val bvalid  = Input(Bool())
  val bready  = Output(Bool())
  val bid     = Input(UInt(idBits.W))
  val bresp   = Input(UInt(2.W))

  val arvalid = Output(Bool())
  val arready = Input(Bool())
  val arid    = Output(UInt(idBits.W))
  val araddr  = Output(UInt(addrBits.W))
  val arlen   = Output(UInt(8.W))
  val arsize  = Output(UInt(3.W))
  val arburst = Output(UInt(2.W))
  val arlock  = Output(Bool())
  val arcache = Output(UInt(4.W))
  val arprot  = Output(UInt(3.W))
  val arqos   = Output(UInt(4.W))

  val rvalid  = Input(Bool())
  val rready  = Output(Bool())
  val rid     = Input(UInt(idBits.W))
  val rdata   = Input(UInt(dataBits.W))
  val rresp   = Input(UInt(2.W))
  val rlast   = Input(Bool())
}

/**
 * Arbitrates the instruction and data ports onto one 64-bit AXI4 master.
 * One transaction in flight at a time; the data port has priority.
 * Instruction reads use ARID 0, data accesses use ID 1.
 */
class AXI4Bridge extends Module {
  val io = IO(new Bundle {
    val imem = Flipped(new MemPort)
    val dmem = Flipped(new MemPort)
    val axi  = new AXI4Master
  })

  val sIdle :: sAR :: sR :: sW :: sB :: Nil = Enum(5)
  val state  = RegInit(sIdle)
  val req    = Reg(new MemReq)
  val isData = Reg(Bool())
  val awDone = Reg(Bool())
  val wDone  = Reg(Bool())

  io.dmem.req.ready := state === sIdle
  io.imem.req.ready := state === sIdle && !io.dmem.req.valid

  when(state === sIdle) {
    when(io.dmem.req.valid || io.imem.req.valid) {
      val r = Mux(io.dmem.req.valid, io.dmem.req.bits, io.imem.req.bits)
      req    := r
      isData := io.dmem.req.valid
      awDone := false.B
      wDone  := false.B
      state  := Mux(r.wen, sW, sAR)
    }
  }

  val hi = req.addr(2)
  val ax = io.axi

  // Read address / data
  ax.arvalid := state === sAR
  ax.arid    := isData.asUInt
  ax.araddr  := req.addr
  ax.arlen   := 0.U
  ax.arsize  := req.size
  ax.arburst := 1.U // INCR
  ax.arlock  := false.B
  ax.arcache := 0.U
  ax.arprot  := Mux(isData, 0.U, 4.U) // instruction access
  ax.arqos   := 0.U
  ax.rready  := state === sR

  // Write address / data / response
  ax.awvalid := state === sW && !awDone
  ax.awid    := 1.U
  ax.awaddr  := req.addr
  ax.awlen   := 0.U
  ax.awsize  := req.size
  ax.awburst := 1.U
  ax.awlock  := false.B
  ax.awcache := 0.U
  ax.awprot  := 0.U
  ax.awqos   := 0.U
  ax.wvalid  := state === sW && !wDone
  ax.wdata   := Cat(req.wdata, req.wdata)
  ax.wstrb   := Mux(hi, Cat(req.wstrb, 0.U(4.W)), Cat(0.U(4.W), req.wstrb))
  ax.wlast   := true.B
  ax.bready  := state === sB

  when(state === sAR && ax.arready) { state := sR }
  when(state === sR && ax.rvalid && ax.rlast) { state := sIdle }

  when(state === sW) {
    val aw = awDone || ax.awready
    val w  = wDone || ax.wready
    awDone := aw
    wDone  := w
    when(aw && w) { state := sB }
  }
  when(state === sB && ax.bvalid) { state := sIdle }

  val rDone   = state === sR && ax.rvalid && ax.rlast
  val bDone   = state === sB && ax.bvalid
  val done    = rDone || bDone

  io.imem.resp.valid := done && !isData
  io.imem.resp.bits  := ax.rdata
  io.dmem.resp.valid := done && isData
  io.dmem.resp.bits  := ax.rdata
}
