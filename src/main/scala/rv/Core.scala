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

/**
 * Classic 5-stage in-order RV32I pipeline: IF, ID, EX, MEM, WB.
 * M/S/U privilege modes with Sv32 translation through small ITLB/DTLBs and a shared
 * hardware page-table walker. Exceptions are taken in EX, or in MEM for data accesses.
 */
class Core extends Module {
  val io = IO(new Bundle {
    val imem   = new MemPort
    val dmem   = new MemPort
    val ptw    = new MemPort
    val commit = Output(new CommitInfo)
  })

  // ---------------------------------------------------------------- pipeline registers
  class IFID extends Bundle {
    val valid = Bool()
    val pc    = UInt(32.W)
    val inst  = UInt(32.W)
    val fault = Valid(UInt(32.W)) // instruction fetch exception cause
  }
  class IDEX extends Bundle {
    val valid = Bool()
    val pc    = UInt(32.W)
    val inst  = UInt(32.W)
    val ctrl  = new CtrlSignals
    val fault = Valid(UInt(32.W))
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
    val result  = UInt(32.W) // ALU / PC+4 / CSR value, or virtual address for memory ops
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
  class WalkFault extends Bundle {
    val valid = Bool()
    val cause = UInt(32.W)
    val vpn   = UInt(20.W)
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
  val itlb  = Module(new TLB(4))
  val dtlb  = Module(new TLB(4))
  val ptw   = Module(new PTW)
  val ipmp  = Module(new PMPChecker)
  val dpmp  = Module(new PMPChecker)
  for (p <- Seq(ipmp, dpmp)) {
    p.io.cfg  := csr.io.pmpCfg
    p.io.addr := csr.io.pmpAddr
  }
  ptw.io.pmpCfg  := csr.io.pmpCfg
  ptw.io.pmpAddr := csr.io.pmpAddr
  ptw.io.satpPpn := csr.io.satpPpn
  io.ptw <> ptw.io.mem

  val priv   = csr.io.priv
  val status = csr.io.status

  // Hazard/control signals, driven below
  val memStall = Wire(Bool())
  val loadUse  = Wire(Bool())
  val redirect = Wire(Valid(UInt(32.W)))
  val tlbFlush = Wire(Bool()) // sfence.vma or satp write

  itlb.io.flush := tlbFlush
  dtlb.io.flush := tlbFlush
  ptw.io.flush  := tlbFlush
  ptw.io.killI  := redirect.valid

  // ---------------------------------------------------------------- IF
  io.imem <> fetch.io.imem
  fetch.io.redirect := redirect
  val stallID = memStall || loadUse
  fetch.io.out.ready := !stallID

  // Fetch address translation (uses the current privilege; MPRV does not apply)
  val fpc        = fetch.io.pc
  val iTranslate = csr.io.satpOn && priv =/= PRV_M
  val iWalkFault = RegInit(invalid(new WalkFault))
  val iEntry     = itlb.io.entry
  val iFaultHere = iWalkFault.valid && iWalkFault.vpn === fpc(31, 12)
  val iPaddr     = Mux(iTranslate, iEntry.paddr(fpc), fpc.pad(34))
  itlb.io.vpn := fpc(31, 12)

  ipmp.io.req.addr := iPaddr
  ipmp.io.req.priv := priv
  ipmp.io.req.r    := false.B
  ipmp.io.req.w    := false.B
  ipmp.io.req.x    := true.B

  val iPageFault   = iTranslate && itlb.io.hit && !MMU.allowed(iEntry, MMU.ACC_FETCH, priv, status.sum, status.mxr)
  val iAccessFault = iPaddr(33, 32) =/= 0.U || ipmp.io.fault
  val xl = fetch.io.xlate
  xl.ready       := !iTranslate || itlb.io.hit || iFaultHere
  xl.paddr       := iPaddr(31, 0)
  xl.fault.valid := (iTranslate && !itlb.io.hit) || iPageFault || iAccessFault
  xl.fault.bits  := Mux(iTranslate && !itlb.io.hit, iWalkFault.cause,
                    Mux(iPageFault, CAUSE_FETCH_PAGE.U, CAUSE_FETCH_ACCESS.U))

  ptw.io.iReq.valid := iTranslate && !itlb.io.hit && !iFaultHere && !redirect.valid
  ptw.io.iReq.bits  := fpc(31, 12)

  val walkI = ptw.io.resp.valid && !ptw.io.resp.bits.isData
  itlb.io.insert.valid := walkI && !ptw.io.resp.bits.fault
  itlb.io.insert.bits  := ptw.io.resp.bits.entry
  when(walkI && ptw.io.resp.bits.fault) {
    // The fetch PC cannot change during a walk, so the fault belongs to its page
    iWalkFault.valid := true.B
    iWalkFault.cause := ptw.io.resp.bits.cause
    iWalkFault.vpn   := fpc(31, 12)
  }
  when(redirect.valid || tlbFlush) { iWalkFault.valid := false.B }

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
  when(ifid.fault.valid) {
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

  // A fault in MEM traps from there and squashes the instruction in EX
  val memExc  = Wire(Bool())
  val exFire  = idex.valid && !memStall && !memExc
  val isCsr   = ec.csr =/= CSR_N
  csr.io.cmd   := ec.csr
  csr.io.write := ec.csr === CSR_W || idex.rs1 =/= 0.U
  csr.io.addr  := idex.inst(31, 20)
  csr.io.wdata := Mux(ec.immType === IMM_Z, idex.imm, rs1v)

  // Privileged instructions. WFI completes immediately, so it is legal at every level.
  val sysIllegal = MuxLookup(ec.sys, false.B)(Seq(
    SYS_MRET   -> (priv =/= PRV_M),
    SYS_SRET   -> (priv === PRV_U || (priv === PRV_S && status.tsr)),
    SYS_SFENCE -> (priv === PRV_U || (priv === PRV_S && status.tvm))
  ))
  val ifault    = idex.fault.valid
  val illegal   = ec.sys === SYS_ILL || (isCsr && csr.io.illegal) || sysIllegal
  val misTarget = brTaken && brTarget(1) // no compressed instructions
  val ecall     = ec.sys === SYS_ECALL
  val ebreak    = ec.sys === SYS_EBREAK
  val exc       = idex.valid && (ifault || illegal || misTarget || ecall || ebreak)
  val mret      = idex.valid && ec.sys === SYS_MRET && !exc
  val sret      = idex.valid && ec.sys === SYS_SRET && !exc
  val csrAddr   = idex.inst(31, 20)
  val satpWrite = isCsr && csr.io.write && csrAddr === CSRAddr.satp.U
  // Refetch younger instructions after these, so fetch sees the new state
  val refetch   = ec.sys === SYS_FENCEI || ec.sys === SYS_SFENCE || satpWrite ||
                  (isCsr && csr.io.write && CSRAddr.isPMP(csrAddr))

  val exCause = Mux(ifault, idex.fault.bits,
                Mux(illegal, CAUSE_ILLEGAL_INST.U,
                Mux(misTarget, CAUSE_FETCH_MISALIGNED.U,
                Mux(ebreak, CAUSE_BREAKPOINT.U, CAUSE_ECALL_U.U + priv))))
  val exTval  = Mux(ifault || ebreak, idex.pc,
                Mux(illegal, idex.inst,
                Mux(misTarget, brTarget, 0.U)))

  val memCause = Wire(UInt(32.W))
  val memTval  = Wire(UInt(32.W))

  csr.io.valid := exFire && isCsr && !exc
  csr.io.trap  := (exFire && exc) || memExc
  csr.io.cause := Mux(memExc, memCause, exCause)
  csr.io.epc   := Mux(memExc, exmem.pc, idex.pc)
  csr.io.tval  := Mux(memExc, memTval, exTval)
  csr.io.mret  := exFire && mret
  csr.io.sret  := exFire && sret
  csr.io.retire   := exFire && !exc
  csr.io.unretire := memExc
  fetch.io.flushLine := exFire && !exc && ec.sys === SYS_FENCEI
  tlbFlush := exFire && !exc && (ec.sys === SYS_SFENCE || satpWrite)

  redirect.valid := memExc || (exFire && (exc || mret || sret || brTaken || refetch))
  redirect.bits  := Mux(memExc || exc, csr.io.trapVec,
                    Mux(mret, csr.io.mepc,
                    Mux(sret, csr.io.sepc,
                    Mux(brTaken, brTarget, pc4))))

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
  // Misaligned accesses are done in hardware: an access inside one aligned 8-byte beat
  // takes one bus transfer; one that crosses into the next beat takes two, each translated
  // and PMP-checked on its own (so it may also cross a page).
  val isMemOp = exmem.valid && exmem.mem =/= M_N
  val isStore = exmem.mem === M_ST
  val va      = exmem.result
  val acc     = Mux(isStore, MMU.ACC_STORE, MMU.ACC_LOAD)

  val off      = va(2, 0)                                   // byte offset in the first beat
  val nbytes   = (1.U(4.W) << exmem.memSize)(3, 0)
  val aligned  = MuxLookup(exmem.memSize, true.B)(Seq(MS_H -> !va(0), MS_W -> (va(1, 0) === 0.U)))
  val twoBeats = off +& nbytes > 8.U
  val part     = RegInit(0.U(1.W))                          // which beat is being accessed
  val beat0    = Reg(UInt(64.W))                            // first beat of a two-beat load
  val curVa    = Mux(part === 1.U, Cat(va(31, 3) + 1.U, 0.U(3.W)), va)

  // Byte lanes over the 16-byte window starting at the first beat
  val sdata    = MuxLookup(exmem.memSize, exmem.sdata)(Seq(
    MS_B -> exmem.sdata(7, 0).pad(32),
    MS_H -> exmem.sdata(15, 0).pad(32)
  ))
  val wlanes   = (sdata.pad(128) << (off << 3))(127, 0)
  val wmask    = (((1.U(16.W) << nbytes) - 1.U) << off)(15, 0)

  // Data translation uses the effective privilege (MPRV makes M-mode act as MPP)
  val dPriv      = Mux(status.mprv && priv === PRV_M, status.mpp, priv)
  val dTranslate = csr.io.satpOn && dPriv =/= PRV_M
  val dWalkFault = RegInit(invalid(new WalkFault))
  val dEntry     = dtlb.io.entry
  val dPaddr     = Mux(dTranslate, dEntry.paddr(curVa), curVa.pad(34))
  val dReady     = !dTranslate || dtlb.io.hit
  dtlb.io.vpn := curVa(31, 12)

  dpmp.io.req.addr := dPaddr
  dpmp.io.req.priv := dPriv
  dpmp.io.req.r    := !isStore
  dpmp.io.req.w    := isStore
  dpmp.io.req.x    := false.B

  val dPageFault = dTranslate && dtlb.io.hit && !MMU.allowed(dEntry, acc, dPriv, status.sum, status.mxr)
  val dAccFault  = dReady && (dPaddr(33, 32) =/= 0.U || dpmp.io.fault)
  memExc   := isMemOp && (dWalkFault.valid || dPageFault || dAccFault)
  memCause := Mux(dWalkFault.valid, dWalkFault.cause,
              Mux(dPageFault, MMU.pageFaultCause(acc), MMU.accessFaultCause(acc)))
  memTval  := curVa // for the second beat, the address of the part that faulted

  ptw.io.dReq.valid      := isMemOp && dTranslate && !dtlb.io.hit && !dWalkFault.valid
  ptw.io.dReq.bits.vpn   := curVa(31, 12)
  ptw.io.dReq.bits.store := isStore

  val walkD = ptw.io.resp.valid && ptw.io.resp.bits.isData
  dtlb.io.insert.valid := walkD && !ptw.io.resp.bits.fault
  dtlb.io.insert.bits  := ptw.io.resp.bits.entry
  when(walkD && ptw.io.resp.bits.fault) {
    dWalkFault.valid := true.B
    dWalkFault.cause := ptw.io.resp.bits.cause
  }
  when(memExc) { dWalkFault.valid := false.B }

  val isMem   = isMemOp && !memExc && dReady
  val reqSent = RegInit(false.B)

  // Aligned accesses keep their natural size; misaligned ones use whole aligned beats
  io.dmem.req.valid      := isMem && !reqSent
  io.dmem.req.bits.addr  := Mux(aligned, dPaddr(31, 0), Cat(dPaddr(31, 3), 0.U(3.W)))
  io.dmem.req.bits.wen   := isStore
  io.dmem.req.bits.size  := Mux(aligned, exmem.memSize, 3.U)
  io.dmem.req.bits.wdata := Mux(part === 1.U, wlanes(127, 64), wlanes(63, 0))
  io.dmem.req.bits.wstrb := Mux(part === 1.U, wmask(15, 8), wmask(7, 0))

  val firstOfTwo = io.dmem.resp.valid && twoBeats && part === 0.U
  val memDone    = io.dmem.resp.valid && !firstOfTwo
  when(io.dmem.req.fire)  { reqSent := true.B }
  when(io.dmem.resp.valid) { reqSent := false.B }
  when(firstOfTwo) {
    part  := 1.U
    beat0 := io.dmem.resp.bits
  }
  when(memDone || memExc) { part := 0.U }
  memStall := isMemOp && !memExc && !memDone

  val window = Cat(io.dmem.resp.bits, Mux(part === 1.U, beat0, io.dmem.resp.bits))
  val rshift = (window >> (off << 3))(31, 0)
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
  bridge.io.ptw  <> core.io.ptw
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
