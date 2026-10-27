package rv

import chisel3._
import chisel3.util._
import Consts._

class CommitInfo extends Bundle {
  val valid = Bool()
  val pc    = UInt(32.W)
  val inst  = UInt(32.W)
  val wen   = Bool()
  val rd    = UInt(5.W)
  val wdata = UInt(32.W)
}

class ALU extends Module {
  val io = IO(new Bundle {
    val op  = Input(UInt(4.W))
    val a   = Input(UInt(32.W))
    val b   = Input(UInt(32.W))
    val out = Output(UInt(32.W))
  })
  val shamt = io.b(4, 0)
  io.out := MuxLookup(io.op, io.a + io.b)(Seq(
    ALU_ADD  -> (io.a + io.b),
    ALU_SUB  -> (io.a - io.b),
    ALU_SLL  -> (io.a << shamt)(31, 0),
    ALU_SLT  -> (io.a.asSInt < io.b.asSInt).asUInt,
    ALU_SLTU -> (io.a < io.b).asUInt,
    ALU_XOR  -> (io.a ^ io.b),
    ALU_SRL  -> (io.a >> shamt),
    ALU_SRA  -> (io.a.asSInt >> shamt).asUInt,
    ALU_OR   -> (io.a | io.b),
    ALU_AND  -> (io.a & io.b),
    ALU_B    -> io.b
  ))
}

/** Classic 5-stage in-order RV32I pipeline: IF, ID, EX, MEM, WB. */
class Core extends Module {
  val io = IO(new Bundle {
    val imem   = new MemPort
    val dmem   = new MemPort
    val commit = Output(new CommitInfo)
  })

  // ---------------------------------------------------------------- pipeline registers
  class IFID extends Bundle {
    val valid = Bool()
    val pc    = UInt(32.W)
    val inst  = UInt(32.W)
  }
  class IDEX extends Bundle {
    val valid = Bool()
    val pc    = UInt(32.W)
    val inst  = UInt(32.W)
    val ctrl  = new CtrlSignals
    val rs1   = UInt(5.W)
    val rs2   = UInt(5.W)
    val rd    = UInt(5.W)
    val imm   = UInt(32.W)
    val rs1v  = UInt(32.W)
    val rs2v  = UInt(32.W)
  }
  class EXMEM extends Bundle {
    val valid   = Bool()
    val pc      = UInt(32.W)
    val inst    = UInt(32.W)
    val mem     = UInt(2.W)
    val memSize = UInt(2.W)
    val memUns  = Bool()
    val wen     = Bool()
    val rd      = UInt(5.W)
    val result  = UInt(32.W) // ALU / PC+4 / CSR value, or effective address for memory ops
    val sdata   = UInt(32.W)
  }
  class MEMWB extends Bundle {
    val valid = Bool()
    val pc    = UInt(32.W)
    val inst  = UInt(32.W)
    val wen   = Bool()
    val rd    = UInt(5.W)
    val wdata = UInt(32.W)
  }

  def invalid[T <: Bundle](b: T): T = {
    val w = Wire(b)
    w := DontCare
    w.elements("valid") := false.B
    w
  }

  val ifid  = RegInit(invalid(new IFID))
  val idex  = RegInit(invalid(new IDEX))
  val exmem = RegInit(invalid(new EXMEM))
  val memwb = RegInit(invalid(new MEMWB))

  val fetch = Module(new Fetch)
  val csr   = Module(new CSRFile)
  val rf    = Mem(32, UInt(32.W))

  // Hazard/control signals, driven below
  val memStall = Wire(Bool())
  val loadUse  = Wire(Bool())
  val redirect = Wire(Valid(UInt(32.W)))

  // ---------------------------------------------------------------- IF
  io.imem <> fetch.io.imem
  fetch.io.redirect := redirect
  val stallID = memStall || loadUse
  fetch.io.out.ready := !stallID

  when(redirect.valid) {
    ifid.valid := false.B
  }.elsewhen(!stallID) {
    ifid.valid := fetch.io.out.valid
    ifid.pc    := fetch.io.out.bits.pc
    ifid.inst  := fetch.io.out.bits.inst
  }

  // ---------------------------------------------------------------- ID
  val dec = Module(new Decoder)
  dec.io.inst := ifid.inst
  val ctrl = dec.io.ctrl
  val idRs1 = ifid.inst(19, 15)
  val idRs2 = ifid.inst(24, 20)
  val idRd  = ifid.inst(11, 7)

  val wbWrite = memwb.valid && memwb.wen && memwb.rd =/= 0.U
  def readReg(r: UInt): UInt =
    Mux(r === 0.U, 0.U, Mux(wbWrite && memwb.rd === r, memwb.wdata, rf(r)))

  loadUse := ifid.valid && idex.valid && idex.ctrl.mem === M_LD && idex.rd =/= 0.U &&
    ((ctrl.useRs1 && idRs1 === idex.rd) || (ctrl.useRs2 && idRs2 === idex.rd))

  // ---------------------------------------------------------------- EX
  def forward(r: UInt, v: UInt): UInt =
    Mux(r =/= 0.U && exmem.valid && exmem.wen && exmem.rd === r, exmem.result,
      Mux(r =/= 0.U && memwb.valid && memwb.wen && memwb.rd === r, memwb.wdata, v))

  val ec   = idex.ctrl
  val rs1v = forward(idex.rs1, idex.rs1v)
  val rs2v = forward(idex.rs2, idex.rs2v)

  val alu = Module(new ALU)
  alu.io.op := ec.aluOp
  alu.io.a  := MuxLookup(ec.aSel, rs1v)(Seq(A_RS1 -> rs1v, A_PC -> idex.pc, A_ZERO -> 0.U))
  alu.io.b  := Mux(ec.bSel === B_IMM, idex.imm, rs2v)

  val brTaken = MuxLookup(ec.br, false.B)(Seq(
    BR_EQ   -> (rs1v === rs2v),
    BR_NE   -> (rs1v =/= rs2v),
    BR_LT   -> (rs1v.asSInt < rs2v.asSInt),
    BR_GE   -> (rs1v.asSInt >= rs2v.asSInt),
    BR_LTU  -> (rs1v < rs2v),
    BR_GEU  -> (rs1v >= rs2v),
    BR_JAL  -> true.B,
    BR_JALR -> true.B
  ))
  val brTarget = Mux(ec.br === BR_JALR, Cat(alu.io.out(31, 1), 0.U(1.W)), idex.pc + idex.imm)
  val pc4      = idex.pc + 4.U

  val exFire  = idex.valid && !memStall
  val isCsr   = ec.csr =/= CSR_N
  csr.io.cmd   := ec.csr
  csr.io.write := ec.csr === CSR_W || idex.rs1 =/= 0.U
  csr.io.addr  := idex.inst(31, 20)
  csr.io.wdata := Mux(ec.immType === IMM_Z, idex.imm, rs1v)

  val illegal = ec.sys === SYS_ILL || (isCsr && csr.io.illegal)
  val exc     = idex.valid && (illegal || ec.sys === SYS_ECALL || ec.sys === SYS_EBREAK)
  val mret    = idex.valid && ec.sys === SYS_MRET

  csr.io.valid := exFire && isCsr && !exc
  csr.io.trap  := exFire && exc
  csr.io.cause := Mux(illegal, CAUSE_ILLEGAL_INST.U,
                  Mux(ec.sys === SYS_EBREAK, CAUSE_BREAKPOINT.U, CAUSE_ECALL_M.U))
  csr.io.epc   := idex.pc
  csr.io.tval  := Mux(illegal, idex.inst, Mux(ec.sys === SYS_EBREAK, idex.pc, 0.U))
  csr.io.mret  := exFire && mret
  fetch.io.flushLine := exFire && ec.sys === SYS_FENCEI

  redirect.valid := exFire && (exc || mret || brTaken || ec.sys === SYS_FENCEI)
  redirect.bits  := Mux(exc, csr.io.mtvec,
                    Mux(mret, csr.io.mepc,
                    Mux(brTaken, brTarget, pc4)))

  val exResult = MuxLookup(ec.wbSel, alu.io.out)(Seq(
    WB_PC4 -> pc4,
    WB_CSR -> csr.io.rdata
  ))

  // ID -> EX register update
  when(memStall) {
    // EX is frozen: capture forwarded operands, since the producers may leave the pipeline
    idex.rs1v := rs1v
    idex.rs2v := rs2v
  }.elsewhen(redirect.valid || loadUse) {
    idex.valid := false.B
  }.otherwise {
    idex.valid := ifid.valid
    idex.pc    := ifid.pc
    idex.inst  := ifid.inst
    idex.ctrl  := ctrl
    idex.rs1   := idRs1
    idex.rs2   := idRs2
    idex.rd    := idRd
    idex.imm   := ImmGen(ifid.inst, ctrl.immType)
    idex.rs1v  := readReg(idRs1)
    idex.rs2v  := readReg(idRs2)
  }

  // EX -> MEM register update
  when(!memStall) {
    exmem.valid   := idex.valid && !exc
    exmem.pc      := idex.pc
    exmem.inst    := idex.inst
    exmem.mem     := ec.mem
    exmem.memSize := ec.memSize
    exmem.memUns  := ec.memUns
    exmem.wen     := ec.wen
    exmem.rd      := idex.rd
    exmem.result  := exResult
    exmem.sdata   := rs2v
  }

  // ---------------------------------------------------------------- MEM
  val isMem   = exmem.valid && exmem.mem =/= M_N
  val reqSent = RegInit(false.B)
  val addr    = exmem.result
  val boff    = addr(1, 0)

  io.dmem.req.valid      := isMem && !reqSent
  io.dmem.req.bits.addr  := addr
  io.dmem.req.bits.wen   := exmem.mem === M_ST
  io.dmem.req.bits.size  := exmem.memSize
  io.dmem.req.bits.wdata := MuxLookup(exmem.memSize, exmem.sdata)(Seq(
    MS_B -> Fill(4, exmem.sdata(7, 0)),
    MS_H -> Fill(2, exmem.sdata(15, 0))
  ))
  io.dmem.req.bits.wstrb := MuxLookup(exmem.memSize, "b1111".U)(Seq(
    MS_B -> (1.U(4.W) << boff),
    MS_H -> (3.U(4.W) << boff)
  ))(3, 0)

  when(io.dmem.req.fire)  { reqSent := true.B }
  when(io.dmem.resp.valid) { reqSent := false.B }
  memStall := isMem && !io.dmem.resp.valid

  val rword  = Mux(addr(2), io.dmem.resp.bits(63, 32), io.dmem.resp.bits(31, 0))
  val rshift = rword >> (boff << 3)
  val loadData = MuxLookup(exmem.memSize, rshift)(Seq(
    MS_B -> Mux(exmem.memUns, rshift(7, 0).pad(32), rshift(7, 0).asSInt.pad(32).asUInt),
    MS_H -> Mux(exmem.memUns, rshift(15, 0).pad(32), rshift(15, 0).asSInt.pad(32).asUInt)
  ))

  memwb.valid := exmem.valid && !memStall
  memwb.pc    := exmem.pc
  memwb.inst  := exmem.inst
  memwb.wen   := exmem.wen
  memwb.rd    := exmem.rd
  memwb.wdata := Mux(exmem.mem === M_LD, loadData, exmem.result)

  // ---------------------------------------------------------------- WB
  when(wbWrite) { rf(memwb.rd) := memwb.wdata }
  csr.io.retire := memwb.valid

  io.commit.valid := memwb.valid
  io.commit.pc    := memwb.pc
  io.commit.inst  := memwb.inst
  io.commit.wen   := wbWrite
  io.commit.rd    := memwb.rd
  io.commit.wdata := memwb.wdata
}

/** SoC top: core + AXI bridge, exposing a single 64-bit AXI4 master. */
class RVTop extends Module {
  val m_axi  = IO(new AXI4Master)
  val commit = IO(Output(new CommitInfo))

  val core   = Module(new Core)
  val bridge = Module(new AXI4Bridge)
  bridge.io.imem <> core.io.imem
  bridge.io.dmem <> core.io.dmem
  m_axi  <> bridge.io.axi
  commit := core.io.commit
}

object Main extends App {
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new RVTop,
    args = Array("--target-dir", "generated"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
