package rv

import chisel3._
import chisel3.util._

/** Simple single-outstanding memory request used between the core and the AXI bridge. */
class MemReq extends Bundle {
  val addr  = UInt(32.W)
  val wen   = Bool()
  val wdata = UInt(64.W)  // already in its byte lanes within the aligned 64-bit beat
  val wstrb = UInt(8.W)
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
 * Arbitrates the data, page-table-walker and instruction ports (in that priority)
 * onto one 64-bit AXI4 master. One transaction in flight at a time.
 * AXI IDs: instruction 0, data 1, page-table walker 2.
 */
class AXI4Bridge extends Module {
  val io = IO(new Bundle {
    val imem = Flipped(new MemPort)
    val dmem = Flipped(new MemPort)
    val ptw  = Flipped(new MemPort)
    val axi  = new AXI4Master
  })

  // Ports in priority order with their AXI IDs
  val ports = Seq(io.dmem -> 1, io.ptw -> 2, io.imem -> 0)

  val sIdle :: sAR :: sR :: sW :: sB :: Nil = Enum(5)
  val state  = RegInit(sIdle)
  val req    = Reg(new MemReq)
  val id     = Reg(UInt(2.W))
  val awDone = Reg(Bool())
  val wDone  = Reg(Bool())

  val valids = ports.map(_._1.req.valid)
  val grant  = PriorityEncoderOH(valids)
  for (((p, _), g) <- ports.zip(grant)) p.req.ready := state === sIdle && g

  when(state === sIdle && valids.reduce(_ || _)) {
    val r = Mux1H(grant, ports.map(_._1.req.bits))
    req    := r
    id     := Mux1H(grant, ports.map(_._2.U(2.W)))
    awDone := false.B
    wDone  := false.B
    state  := Mux(r.wen, sW, sAR)
  }

  val ax = io.axi

  // Read address / data
  ax.arvalid := state === sAR
  ax.arid    := id
  ax.araddr  := req.addr
  ax.arlen   := 0.U
  ax.arsize  := req.size
  ax.arburst := 1.U // INCR
  ax.arlock  := false.B
  ax.arcache := 0.U
  ax.arprot  := Mux(id === 0.U, 4.U, 0.U) // instruction access
  ax.arqos   := 0.U
  ax.rready  := state === sR

  // Write address / data / response
  ax.awvalid := state === sW && !awDone
  ax.awid    := id
  ax.awaddr  := req.addr
  ax.awlen   := 0.U
  ax.awsize  := req.size
  ax.awburst := 1.U
  ax.awlock  := false.B
  ax.awcache := 0.U
  ax.awprot  := 0.U
  ax.awqos   := 0.U
  ax.wvalid  := state === sW && !wDone
  ax.wdata   := req.wdata
  ax.wstrb   := req.wstrb
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

  for ((p, pid) <- ports) {
    p.resp.valid := done && id === pid.U
    p.resp.bits  := ax.rdata
  }
}
