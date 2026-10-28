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
    val op  = Input(UInt(ALU_W.W))
    val a   = Input(UInt(32.W))
    val b   = Input(UInt(32.W))
    val out = Output(UInt(32.W))
  })
  val a     = io.a
  val b     = io.b
  val shamt = b(4, 0)
  val bit   = 1.U(32.W) << shamt
  val lt    = a.asSInt < b.asSInt
  val ltu   = a < b
  def bytes(x: UInt) = (0 until 4).map(i => x(8 * i + 7, 8 * i))
  io.out := MuxLookup(io.op, a + b)(Seq(
    ALU_ADD    -> (a + b),
    ALU_SUB    -> (a - b),
    ALU_SLL    -> (a << shamt)(31, 0),
    ALU_SLT    -> lt.asUInt,
    ALU_SLTU   -> ltu.asUInt,
    ALU_XOR    -> (a ^ b),
    ALU_SRL    -> (a >> shamt),
    ALU_SRA    -> (a.asSInt >> shamt).asUInt,
    ALU_OR     -> (a | b),
    ALU_AND    -> (a & b),
    ALU_B      -> b,
    // Zba
    ALU_SH1ADD -> ((a << 1)(31, 0) + b),
    ALU_SH2ADD -> ((a << 2)(31, 0) + b),
    ALU_SH3ADD -> ((a << 3)(31, 0) + b),
    // Zbb
    ALU_ANDN   -> (a & ~b),
    ALU_ORN    -> (a | ~b),
    ALU_XNOR   -> ~(a ^ b),
    ALU_CLZ    -> Mux(a === 0.U, 32.U, PriorityEncoder(Reverse(a))),
    ALU_CTZ    -> Mux(a === 0.U, 32.U, PriorityEncoder(a)),
    ALU_CPOP   -> PopCount(a),
    ALU_MAX    -> Mux(lt, b, a),
    ALU_MAXU   -> Mux(ltu, b, a),
    ALU_MIN    -> Mux(lt, a, b),
    ALU_MINU   -> Mux(ltu, a, b),
    ALU_SEXTB  -> a(7, 0).asSInt.pad(32).asUInt,
    ALU_SEXTH  -> a(15, 0).asSInt.pad(32).asUInt,
    ALU_ZEXTH  -> a(15, 0).pad(32),
    ALU_ROL    -> (Cat(a, a) << shamt)(63, 32),
    ALU_ROR    -> (Cat(a, a) >> shamt)(31, 0),
    ALU_ORCB   -> Cat(bytes(a).reverse.map(x => Fill(8, x.orR))),
    ALU_REV8   -> Cat(bytes(a)),
    // Zbs
    ALU_BCLR   -> (a & ~bit),
    ALU_BEXT   -> ((a >> shamt) & 1.U),
    ALU_BINV   -> (a ^ bit),
    ALU_BSET   -> (a | bit)
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
    val fault = Bool() // instruction access fault (PMP)
  }
  class IDEX extends Bundle {
    val valid = Bool()
    val pc    = UInt(32.W)
    val inst  = UInt(32.W)
    val ctrl  = new CtrlSignals
    val fault = Bool()
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
  val ipmp  = Module(new PMPChecker)
  val dpmp  = Module(new PMPChecker)
  for (p <- Seq(ipmp, dpmp)) {
    p.io.cfg  := csr.io.pmpCfg
    p.io.addr := csr.io.pmpAddr
  }

  // Hazard/control signals, driven below
  val memStall = Wire(Bool())
  val loadUse  = Wire(Bool())
  val redirect = Wire(Valid(UInt(32.W)))

  // ---------------------------------------------------------------- IF
  io.imem <> fetch.io.imem
  fetch.io.redirect := redirect
  ipmp.io.req.addr := fetch.io.pc
  ipmp.io.req.r    := false.B
  ipmp.io.req.w    := false.B
  ipmp.io.req.x    := true.B
  fetch.io.pcFault := ipmp.io.fault
  val stallID = memStall || loadUse
  fetch.io.out.ready := !stallID

  when(redirect.valid) {
    ifid.valid := false.B
  }.elsewhen(!stallID) {
    ifid.valid := fetch.io.out.valid
    ifid.pc    := fetch.io.out.bits.pc
    ifid.inst  := fetch.io.out.bits.inst
    ifid.fault := fetch.io.out.bits.fault
  }

  // ---------------------------------------------------------------- ID
  val dec = Module(new Decoder)
  dec.io.inst := ifid.inst
  val ctrl = WireInit(dec.io.ctrl)
  when(ifid.fault) {
    // No instruction was fetched: make it a no-op that only carries the fault to EX
    ctrl.mem    := M_N
    ctrl.wen    := false.B
    ctrl.csr    := CSR_N
    ctrl.br     := BR_N
    ctrl.sys    := SYS_N
    ctrl.useRs1 := false.B
    ctrl.useRs2 := false.B
  }
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

  // A PMP fault in MEM traps from there and squashes the instruction in EX
  val memExc  = Wire(Bool())
  val exFire  = idex.valid && !memStall && !memExc
  val isCsr   = ec.csr =/= CSR_N
  csr.io.cmd   := ec.csr
  csr.io.write := ec.csr === CSR_W || idex.rs1 =/= 0.U
  csr.io.addr  := idex.inst(31, 20)
  csr.io.wdata := Mux(ec.immType === IMM_Z, idex.imm, rs1v)

  val ifault  = idex.fault
  val illegal = ec.sys === SYS_ILL || (isCsr && csr.io.illegal)
  val exc     = idex.valid && (ifault || illegal || ec.sys === SYS_ECALL || ec.sys === SYS_EBREAK)
  val mret    = idex.valid && ec.sys === SYS_MRET
  // Writing a PMP CSR refetches younger instructions so they are checked with the new settings
  val pmpWrite = isCsr && csr.io.write && CSRAddr.isPMP(csr.io.addr)

  val exCause = Mux(ifault, CAUSE_FETCH_ACCESS.U,
                Mux(illegal, CAUSE_ILLEGAL_INST.U,
                Mux(ec.sys === SYS_EBREAK, CAUSE_BREAKPOINT.U, CAUSE_ECALL_M.U)))
  val exTval  = Mux(ifault || ec.sys === SYS_EBREAK, idex.pc, Mux(illegal, idex.inst, 0.U))

  val memCause = Wire(UInt(32.W))
  val memTval  = Wire(UInt(32.W))

  csr.io.valid := exFire && isCsr && !exc
  csr.io.trap  := (exFire && exc) || memExc
  csr.io.cause := Mux(memExc, memCause, exCause)
  csr.io.epc   := Mux(memExc, exmem.pc, idex.pc)
  csr.io.tval  := Mux(memExc, memTval, exTval)
  csr.io.mret  := exFire && mret
  fetch.io.flushLine := exFire && ec.sys === SYS_FENCEI

  redirect.valid := memExc || (exFire && (exc || mret || brTaken || ec.sys === SYS_FENCEI || pmpWrite))
  redirect.bits  := Mux(memExc || exc, csr.io.mtvec,
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
    idex.fault := ifid.fault
    idex.rs1   := idRs1
    idex.rs2   := idRs2
    idex.rd    := idRd
    idex.imm   := ImmGen(ifid.inst, ctrl.immType)
    idex.rs1v  := readReg(idRs1)
    idex.rs2v  := readReg(idRs2)
  }

  // EX -> MEM register update
  when(!memStall) {
    exmem.valid   := idex.valid && !exc && !memExc
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
  val addr    = exmem.result
  val boff    = addr(1, 0)
  val isStore = exmem.mem === M_ST
  dpmp.io.req.addr := addr
  dpmp.io.req.r    := exmem.mem === M_LD
  dpmp.io.req.w    := isStore
  dpmp.io.req.x    := false.B
  memExc   := exmem.valid && exmem.mem =/= M_N && dpmp.io.fault
  memCause := Mux(isStore, CAUSE_STORE_ACCESS.U, CAUSE_LOAD_ACCESS.U)
  memTval  := addr

  val isMem   = exmem.valid && exmem.mem =/= M_N && !memExc
  val reqSent = RegInit(false.B)

  io.dmem.req.valid      := isMem && !reqSent
  io.dmem.req.bits.addr  := addr
  io.dmem.req.bits.wen   := isStore
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

  memwb.valid := exmem.valid && !memStall && !memExc
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
