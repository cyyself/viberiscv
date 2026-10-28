package rv

import chisel3._
import chisel3.util._
import Consts._

object Instructions {
  def LUI     = BitPat("b?????????????????????????0110111")
  def AUIPC   = BitPat("b?????????????????????????0010111")
  def JAL     = BitPat("b?????????????????????????1101111")
  def JALR    = BitPat("b?????????????????000?????1100111")
  def BEQ     = BitPat("b?????????????????000?????1100011")
  def BNE     = BitPat("b?????????????????001?????1100011")
  def BLT     = BitPat("b?????????????????100?????1100011")
  def BGE     = BitPat("b?????????????????101?????1100011")
  def BLTU    = BitPat("b?????????????????110?????1100011")
  def BGEU    = BitPat("b?????????????????111?????1100011")
  def LB      = BitPat("b?????????????????000?????0000011")
  def LH      = BitPat("b?????????????????001?????0000011")
  def LW      = BitPat("b?????????????????010?????0000011")
  def LBU     = BitPat("b?????????????????100?????0000011")
  def LHU     = BitPat("b?????????????????101?????0000011")
  def SB      = BitPat("b?????????????????000?????0100011")
  def SH      = BitPat("b?????????????????001?????0100011")
  def SW      = BitPat("b?????????????????010?????0100011")
  def ADDI    = BitPat("b?????????????????000?????0010011")
  def SLTI    = BitPat("b?????????????????010?????0010011")
  def SLTIU   = BitPat("b?????????????????011?????0010011")
  def XORI    = BitPat("b?????????????????100?????0010011")
  def ORI     = BitPat("b?????????????????110?????0010011")
  def ANDI    = BitPat("b?????????????????111?????0010011")
  def SLLI    = BitPat("b0000000??????????001?????0010011")
  def SRLI    = BitPat("b0000000??????????101?????0010011")
  def SRAI    = BitPat("b0100000??????????101?????0010011")
  def ADD     = BitPat("b0000000??????????000?????0110011")
  def SUB     = BitPat("b0100000??????????000?????0110011")
  def SLL     = BitPat("b0000000??????????001?????0110011")
  def SLT     = BitPat("b0000000??????????010?????0110011")
  def SLTU    = BitPat("b0000000??????????011?????0110011")
  def XOR     = BitPat("b0000000??????????100?????0110011")
  def SRL     = BitPat("b0000000??????????101?????0110011")
  def SRA     = BitPat("b0100000??????????101?????0110011")
  def OR      = BitPat("b0000000??????????110?????0110011")
  def AND     = BitPat("b0000000??????????111?????0110011")
  def FENCE   = BitPat("b?????????????????000?????0001111")
  def FENCE_I = BitPat("b?????????????????001?????0001111")
  def ECALL   = BitPat("b00000000000000000000000001110011")
  def EBREAK  = BitPat("b00000000000100000000000001110011")
  def MRET    = BitPat("b00110000001000000000000001110011")
  def SRET    = BitPat("b00010000001000000000000001110011")
  def SFENCE_VMA = BitPat("b0001001??????????000000001110011")
  def WFI     = BitPat("b00010000010100000000000001110011")
  def CSRRW   = BitPat("b?????????????????001?????1110011")
  def CSRRS   = BitPat("b?????????????????010?????1110011")
  def CSRRC   = BitPat("b?????????????????011?????1110011")
  def CSRRWI  = BitPat("b?????????????????101?????1110011")
  def CSRRSI  = BitPat("b?????????????????110?????1110011")
  def CSRRCI  = BitPat("b?????????????????111?????1110011")

  // Zba
  def SH1ADD  = BitPat("b0010000??????????010?????0110011")
  def SH2ADD  = BitPat("b0010000??????????100?????0110011")
  def SH3ADD  = BitPat("b0010000??????????110?????0110011")
  // Zbb
  def ANDN    = BitPat("b0100000??????????111?????0110011")
  def ORN     = BitPat("b0100000??????????110?????0110011")
  def XNOR    = BitPat("b0100000??????????100?????0110011")
  def CLZ     = BitPat("b011000000000?????001?????0010011")
  def CTZ     = BitPat("b011000000001?????001?????0010011")
  def CPOP    = BitPat("b011000000010?????001?????0010011")
  def MAX     = BitPat("b0000101??????????110?????0110011")
  def MAXU    = BitPat("b0000101??????????111?????0110011")
  def MIN     = BitPat("b0000101??????????100?????0110011")
  def MINU    = BitPat("b0000101??????????101?????0110011")
  def SEXT_B  = BitPat("b011000000100?????001?????0010011")
  def SEXT_H  = BitPat("b011000000101?????001?????0010011")
  def ZEXT_H  = BitPat("b000010000000?????100?????0110011")
  def ROL     = BitPat("b0110000??????????001?????0110011")
  def ROR     = BitPat("b0110000??????????101?????0110011")
  def RORI    = BitPat("b0110000??????????101?????0010011")
  def ORC_B   = BitPat("b001010000111?????101?????0010011")
  def REV8    = BitPat("b011010011000?????101?????0010011")
  // Zbs
  def BCLR    = BitPat("b0100100??????????001?????0110011")
  def BCLRI   = BitPat("b0100100??????????001?????0010011")
  def BEXT    = BitPat("b0100100??????????101?????0110011")
  def BEXTI   = BitPat("b0100100??????????101?????0010011")
  def BINV    = BitPat("b0110100??????????001?????0110011")
  def BINVI   = BitPat("b0110100??????????001?????0010011")
  def BSET    = BitPat("b0010100??????????001?????0110011")
  def BSETI   = BitPat("b0010100??????????001?????0010011")
}

class CtrlSignals extends Bundle {
  val legal   = Bool()
  val aSel    = UInt(2.W)
  val bSel    = UInt(1.W)
  val immType = UInt(3.W)
  val aluOp   = UInt(ALU_W.W)
  val br      = UInt(4.W)
  val mem     = UInt(2.W)
  val memSize = UInt(2.W)
  val memUns  = Bool()
  val wbSel   = UInt(2.W)
  val wen     = Bool()
  val csr     = UInt(2.W)
  val sys     = UInt(SYS_W.W)
  val useRs1  = Bool()
  val useRs2  = Bool()
}

class Decoder extends Module {
  val io = IO(new Bundle {
    val inst = Input(UInt(32.W))
    val ctrl = Output(new CtrlSignals)
  })
  import Instructions._

  val Y = true.B
  val N = false.B

  //                       legal aSel    bSel   imm    aluOp     br       mem   msize uns wbSel   wen csr    sys          rs1 rs2
  val default = List(N, A_X, B_X, IMM_X, ALU_X,    BR_N, M_N, MS_X, N, WB_X, N, CSR_N, SYS_ILL, N, N)
  val table = Array(
    LUI     -> List(Y, A_ZERO, B_IMM, IMM_U, ALU_B,    BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      N, N),
    AUIPC   -> List(Y, A_PC,   B_IMM, IMM_U, ALU_ADD,  BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      N, N),
    JAL     -> List(Y, A_PC,   B_IMM, IMM_J, ALU_ADD,  BR_JAL,  M_N,  MS_X, N, WB_PC4, Y, CSR_N, SYS_N,      N, N),
    JALR    -> List(Y, A_RS1,  B_IMM, IMM_I, ALU_ADD,  BR_JALR, M_N,  MS_X, N, WB_PC4, Y, CSR_N, SYS_N,      Y, N),
    BEQ     -> List(Y, A_PC,   B_IMM, IMM_B, ALU_ADD,  BR_EQ,   M_N,  MS_X, N, WB_X,   N, CSR_N, SYS_N,      Y, Y),
    BNE     -> List(Y, A_PC,   B_IMM, IMM_B, ALU_ADD,  BR_NE,   M_N,  MS_X, N, WB_X,   N, CSR_N, SYS_N,      Y, Y),
    BLT     -> List(Y, A_PC,   B_IMM, IMM_B, ALU_ADD,  BR_LT,   M_N,  MS_X, N, WB_X,   N, CSR_N, SYS_N,      Y, Y),
    BGE     -> List(Y, A_PC,   B_IMM, IMM_B, ALU_ADD,  BR_GE,   M_N,  MS_X, N, WB_X,   N, CSR_N, SYS_N,      Y, Y),
    BLTU    -> List(Y, A_PC,   B_IMM, IMM_B, ALU_ADD,  BR_LTU,  M_N,  MS_X, N, WB_X,   N, CSR_N, SYS_N,      Y, Y),
    BGEU    -> List(Y, A_PC,   B_IMM, IMM_B, ALU_ADD,  BR_GEU,  M_N,  MS_X, N, WB_X,   N, CSR_N, SYS_N,      Y, Y),
    LB      -> List(Y, A_RS1,  B_IMM, IMM_I, ALU_ADD,  BR_N,    M_LD, MS_B, N, WB_MEM, Y, CSR_N, SYS_N,      Y, N),
    LH      -> List(Y, A_RS1,  B_IMM, IMM_I, ALU_ADD,  BR_N,    M_LD, MS_H, N, WB_MEM, Y, CSR_N, SYS_N,      Y, N),
    LW      -> List(Y, A_RS1,  B_IMM, IMM_I, ALU_ADD,  BR_N,    M_LD, MS_W, N, WB_MEM, Y, CSR_N, SYS_N,      Y, N),
    LBU     -> List(Y, A_RS1,  B_IMM, IMM_I, ALU_ADD,  BR_N,    M_LD, MS_B, Y, WB_MEM, Y, CSR_N, SYS_N,      Y, N),
    LHU     -> List(Y, A_RS1,  B_IMM, IMM_I, ALU_ADD,  BR_N,    M_LD, MS_H, Y, WB_MEM, Y, CSR_N, SYS_N,      Y, N),
    SB      -> List(Y, A_RS1,  B_IMM, IMM_S, ALU_ADD,  BR_N,    M_ST, MS_B, N, WB_X,   N, CSR_N, SYS_N,      Y, Y),
    SH      -> List(Y, A_RS1,  B_IMM, IMM_S, ALU_ADD,  BR_N,    M_ST, MS_H, N, WB_X,   N, CSR_N, SYS_N,      Y, Y),
    SW      -> List(Y, A_RS1,  B_IMM, IMM_S, ALU_ADD,  BR_N,    M_ST, MS_W, N, WB_X,   N, CSR_N, SYS_N,      Y, Y),
    ADDI    -> List(Y, A_RS1,  B_IMM, IMM_I, ALU_ADD,  BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, N),
    SLTI    -> List(Y, A_RS1,  B_IMM, IMM_I, ALU_SLT,  BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, N),
    SLTIU   -> List(Y, A_RS1,  B_IMM, IMM_I, ALU_SLTU, BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, N),
    XORI    -> List(Y, A_RS1,  B_IMM, IMM_I, ALU_XOR,  BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, N),
    ORI     -> List(Y, A_RS1,  B_IMM, IMM_I, ALU_OR,   BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, N),
    ANDI    -> List(Y, A_RS1,  B_IMM, IMM_I, ALU_AND,  BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, N),
    SLLI    -> List(Y, A_RS1,  B_IMM, IMM_I, ALU_SLL,  BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, N),
    SRLI    -> List(Y, A_RS1,  B_IMM, IMM_I, ALU_SRL,  BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, N),
    SRAI    -> List(Y, A_RS1,  B_IMM, IMM_I, ALU_SRA,  BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, N),
    ADD     -> List(Y, A_RS1,  B_RS2, IMM_X, ALU_ADD,  BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, Y),
    SUB     -> List(Y, A_RS1,  B_RS2, IMM_X, ALU_SUB,  BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, Y),
    SLL     -> List(Y, A_RS1,  B_RS2, IMM_X, ALU_SLL,  BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, Y),
    SLT     -> List(Y, A_RS1,  B_RS2, IMM_X, ALU_SLT,  BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, Y),
    SLTU    -> List(Y, A_RS1,  B_RS2, IMM_X, ALU_SLTU, BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, Y),
    XOR     -> List(Y, A_RS1,  B_RS2, IMM_X, ALU_XOR,  BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, Y),
    SRL     -> List(Y, A_RS1,  B_RS2, IMM_X, ALU_SRL,  BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, Y),
    SRA     -> List(Y, A_RS1,  B_RS2, IMM_X, ALU_SRA,  BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, Y),
    OR      -> List(Y, A_RS1,  B_RS2, IMM_X, ALU_OR,   BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, Y),
    AND     -> List(Y, A_RS1,  B_RS2, IMM_X, ALU_AND,  BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, Y),
    FENCE   -> List(Y, A_X,    B_X,   IMM_X, ALU_X,    BR_N,    M_N,  MS_X, N, WB_X,   N, CSR_N, SYS_N,      N, N),
    FENCE_I -> List(Y, A_X,    B_X,   IMM_X, ALU_X,    BR_N,    M_N,  MS_X, N, WB_X,   N, CSR_N, SYS_FENCEI, N, N),
    ECALL   -> List(Y, A_X,    B_X,   IMM_X, ALU_X,    BR_N,    M_N,  MS_X, N, WB_X,   N, CSR_N, SYS_ECALL,  N, N),
    EBREAK  -> List(Y, A_X,    B_X,   IMM_X, ALU_X,    BR_N,    M_N,  MS_X, N, WB_X,   N, CSR_N, SYS_EBREAK, N, N),
    MRET    -> List(Y, A_X,    B_X,   IMM_X, ALU_X,    BR_N,    M_N,  MS_X, N, WB_X,   N, CSR_N, SYS_MRET,   N, N),
    SRET    -> List(Y, A_X,    B_X,   IMM_X, ALU_X,    BR_N,    M_N,  MS_X, N, WB_X,   N, CSR_N, SYS_SRET,   N, N),
    SFENCE_VMA -> List(Y, A_X,    B_X,   IMM_X, ALU_X,    BR_N,    M_N,  MS_X, N, WB_X,   N, CSR_N, SYS_SFENCE, N, N),
    WFI     -> List(Y, A_X,    B_X,   IMM_X, ALU_X,    BR_N,    M_N,  MS_X, N, WB_X,   N, CSR_N, SYS_WFI,    N, N),
    CSRRW   -> List(Y, A_RS1,  B_X,   IMM_X, ALU_X,    BR_N,    M_N,  MS_X, N, WB_CSR, Y, CSR_W, SYS_N,      Y, N),
    CSRRS   -> List(Y, A_RS1,  B_X,   IMM_X, ALU_X,    BR_N,    M_N,  MS_X, N, WB_CSR, Y, CSR_S, SYS_N,      Y, N),
    CSRRC   -> List(Y, A_RS1,  B_X,   IMM_X, ALU_X,    BR_N,    M_N,  MS_X, N, WB_CSR, Y, CSR_C, SYS_N,      Y, N),
    CSRRWI  -> List(Y, A_X,    B_X,   IMM_Z, ALU_X,    BR_N,    M_N,  MS_X, N, WB_CSR, Y, CSR_W, SYS_N,      N, N),
    CSRRSI  -> List(Y, A_X,    B_X,   IMM_Z, ALU_X,    BR_N,    M_N,  MS_X, N, WB_CSR, Y, CSR_S, SYS_N,      N, N),
    CSRRCI  -> List(Y, A_X,    B_X,   IMM_Z, ALU_X,    BR_N,    M_N,  MS_X, N, WB_CSR, Y, CSR_C, SYS_N,      N, N),
    // Zba
    SH1ADD  -> List(Y, A_RS1,  B_RS2, IMM_X, ALU_SH1ADD, BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, Y),
    SH2ADD  -> List(Y, A_RS1,  B_RS2, IMM_X, ALU_SH2ADD, BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, Y),
    SH3ADD  -> List(Y, A_RS1,  B_RS2, IMM_X, ALU_SH3ADD, BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, Y),
    // Zbb
    ANDN    -> List(Y, A_RS1,  B_RS2, IMM_X, ALU_ANDN, BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, Y),
    ORN     -> List(Y, A_RS1,  B_RS2, IMM_X, ALU_ORN,  BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, Y),
    XNOR    -> List(Y, A_RS1,  B_RS2, IMM_X, ALU_XNOR, BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, Y),
    CLZ     -> List(Y, A_RS1,  B_X,   IMM_X, ALU_CLZ,  BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, N),
    CTZ     -> List(Y, A_RS1,  B_X,   IMM_X, ALU_CTZ,  BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, N),
    CPOP    -> List(Y, A_RS1,  B_X,   IMM_X, ALU_CPOP, BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, N),
    MAX     -> List(Y, A_RS1,  B_RS2, IMM_X, ALU_MAX,  BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, Y),
    MAXU    -> List(Y, A_RS1,  B_RS2, IMM_X, ALU_MAXU, BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, Y),
    MIN     -> List(Y, A_RS1,  B_RS2, IMM_X, ALU_MIN,  BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, Y),
    MINU    -> List(Y, A_RS1,  B_RS2, IMM_X, ALU_MINU, BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, Y),
    SEXT_B  -> List(Y, A_RS1,  B_X,   IMM_X, ALU_SEXTB, BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, N),
    SEXT_H  -> List(Y, A_RS1,  B_X,   IMM_X, ALU_SEXTH, BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, N),
    ZEXT_H  -> List(Y, A_RS1,  B_X,   IMM_X, ALU_ZEXTH, BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, N),
    ROL     -> List(Y, A_RS1,  B_RS2, IMM_X, ALU_ROL,  BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, Y),
    ROR     -> List(Y, A_RS1,  B_RS2, IMM_X, ALU_ROR,  BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, Y),
    RORI    -> List(Y, A_RS1,  B_IMM, IMM_I, ALU_ROR,  BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, N),
    ORC_B   -> List(Y, A_RS1,  B_X,   IMM_X, ALU_ORCB, BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, N),
    REV8    -> List(Y, A_RS1,  B_X,   IMM_X, ALU_REV8, BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, N),
    // Zbs
    BCLR    -> List(Y, A_RS1,  B_RS2, IMM_X, ALU_BCLR, BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, Y),
    BCLRI   -> List(Y, A_RS1,  B_IMM, IMM_I, ALU_BCLR, BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, N),
    BEXT    -> List(Y, A_RS1,  B_RS2, IMM_X, ALU_BEXT, BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, Y),
    BEXTI   -> List(Y, A_RS1,  B_IMM, IMM_I, ALU_BEXT, BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, N),
    BINV    -> List(Y, A_RS1,  B_RS2, IMM_X, ALU_BINV, BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, Y),
    BINVI   -> List(Y, A_RS1,  B_IMM, IMM_I, ALU_BINV, BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, N),
    BSET    -> List(Y, A_RS1,  B_RS2, IMM_X, ALU_BSET, BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, Y),
    BSETI   -> List(Y, A_RS1,  B_IMM, IMM_I, ALU_BSET, BR_N,    M_N,  MS_X, N, WB_ALU, Y, CSR_N, SYS_N,      Y, N),
  )

  val sigs = ListLookup(io.inst, default, table)
  val c = io.ctrl
  Seq(c.legal, c.aSel, c.bSel, c.immType, c.aluOp, c.br, c.mem, c.memSize, c.memUns,
      c.wbSel, c.wen, c.csr, c.sys, c.useRs1, c.useRs2).zip(sigs).foreach { case (d, s) => d := s }
}

object ImmGen {
  def apply(inst: UInt, sel: UInt): UInt = {
    val i = Cat(Fill(21, inst(31)), inst(30, 20))
    val s = Cat(Fill(21, inst(31)), inst(30, 25), inst(11, 7))
    val b = Cat(Fill(20, inst(31)), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W))
    val u = Cat(inst(31, 12), 0.U(12.W))
    val j = Cat(Fill(12, inst(31)), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W))
    val z = Cat(0.U(27.W), inst(19, 15))
    MuxLookup(sel, i)(Seq(IMM_I -> i, IMM_S -> s, IMM_B -> b, IMM_U -> u, IMM_J -> j, IMM_Z -> z))
  }
}
