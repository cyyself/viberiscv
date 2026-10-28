package rv

import chisel3._
import chisel3.util._

object Consts {
  val XLEN       = 32
  val RESET_PC   = 0x10000L

  // ALU operations
  val ALU_W      = 6
  val ALU_X      = 0.U(ALU_W.W)
  val ALU_ADD    = 0.U(ALU_W.W)
  val ALU_SUB    = 1.U(ALU_W.W)
  val ALU_SLL    = 2.U(ALU_W.W)
  val ALU_SLT    = 3.U(ALU_W.W)
  val ALU_SLTU   = 4.U(ALU_W.W)
  val ALU_XOR    = 5.U(ALU_W.W)
  val ALU_SRL    = 6.U(ALU_W.W)
  val ALU_SRA    = 7.U(ALU_W.W)
  val ALU_OR     = 8.U(ALU_W.W)
  val ALU_AND    = 9.U(ALU_W.W)
  val ALU_B      = 10.U(ALU_W.W) // pass operand B (LUI)
  val ALU_SH1ADD = 11.U(ALU_W.W) // Zba
  val ALU_SH2ADD = 12.U(ALU_W.W)
  val ALU_SH3ADD = 13.U(ALU_W.W)
  val ALU_ANDN   = 14.U(ALU_W.W) // Zbb
  val ALU_ORN    = 15.U(ALU_W.W)
  val ALU_XNOR   = 16.U(ALU_W.W)
  val ALU_CLZ    = 17.U(ALU_W.W)
  val ALU_CTZ    = 18.U(ALU_W.W)
  val ALU_CPOP   = 19.U(ALU_W.W)
  val ALU_MAX    = 20.U(ALU_W.W)
  val ALU_MAXU   = 21.U(ALU_W.W)
  val ALU_MIN    = 22.U(ALU_W.W)
  val ALU_MINU   = 23.U(ALU_W.W)
  val ALU_SEXTB  = 24.U(ALU_W.W)
  val ALU_SEXTH  = 25.U(ALU_W.W)
  val ALU_ZEXTH  = 26.U(ALU_W.W)
  val ALU_ROL    = 27.U(ALU_W.W)
  val ALU_ROR    = 28.U(ALU_W.W)
  val ALU_ORCB   = 29.U(ALU_W.W)
  val ALU_REV8   = 30.U(ALU_W.W)
  val ALU_BCLR   = 31.U(ALU_W.W) // Zbs
  val ALU_BEXT   = 32.U(ALU_W.W)
  val ALU_BINV   = 33.U(ALU_W.W)
  val ALU_BSET   = 34.U(ALU_W.W)

  // Operand A select
  val A_X   = 0.U(2.W)
  val A_RS1 = 0.U(2.W)
  val A_PC  = 1.U(2.W)
  val A_ZERO= 2.U(2.W)

  // Operand B select
  val B_X   = 0.U(1.W)
  val B_RS2 = 0.U(1.W)
  val B_IMM = 1.U(1.W)

  // Immediate types
  val IMM_X = 0.U(3.W)
  val IMM_I = 0.U(3.W)
  val IMM_S = 1.U(3.W)
  val IMM_B = 2.U(3.W)
  val IMM_U = 3.U(3.W)
  val IMM_J = 4.U(3.W)
  val IMM_Z = 5.U(3.W) // CSR zero-extended uimm (rs1 field)

  // Branch / jump type
  val BR_N    = 0.U(4.W)
  val BR_EQ   = 1.U(4.W)
  val BR_NE   = 2.U(4.W)
  val BR_LT   = 3.U(4.W)
  val BR_GE   = 4.U(4.W)
  val BR_LTU  = 5.U(4.W)
  val BR_GEU  = 6.U(4.W)
  val BR_JAL  = 7.U(4.W)
  val BR_JALR = 8.U(4.W)

  // Memory operation
  val M_N  = 0.U(2.W)
  val M_LD = 1.U(2.W)
  val M_ST = 2.U(2.W)

  // Memory size (funct3[1:0]); unsigned is funct3[2]
  val MS_X = 0.U(2.W)
  val MS_B = 0.U(2.W)
  val MS_H = 1.U(2.W)
  val MS_W = 2.U(2.W)

  // Writeback select
  val WB_X   = 0.U(2.W)
  val WB_ALU = 0.U(2.W)
  val WB_PC4 = 1.U(2.W)
  val WB_CSR = 2.U(2.W)
  val WB_MEM = 3.U(2.W)

  // CSR command
  val CSR_N = 0.U(2.W)
  val CSR_W = 1.U(2.W)
  val CSR_S = 2.U(2.W)
  val CSR_C = 3.U(2.W)

  // System / special instructions
  val SYS_W      = 4
  val SYS_N      = 0.U(SYS_W.W)
  val SYS_ECALL  = 1.U(SYS_W.W)
  val SYS_EBREAK = 2.U(SYS_W.W)
  val SYS_MRET   = 3.U(SYS_W.W)
  val SYS_FENCEI = 4.U(SYS_W.W)
  val SYS_WFI    = 5.U(SYS_W.W)
  val SYS_SRET   = 6.U(SYS_W.W)
  val SYS_SFENCE = 7.U(SYS_W.W)
  val SYS_ILL    = 15.U(SYS_W.W)

  // Privilege levels
  val PRV_U = 0.U(2.W)
  val PRV_S = 1.U(2.W)
  val PRV_M = 3.U(2.W)

  // Exception causes
  val CAUSE_FETCH_MISALIGNED = 0
  val CAUSE_FETCH_ACCESS     = 1
  val CAUSE_ILLEGAL_INST     = 2
  val CAUSE_BREAKPOINT       = 3
  val CAUSE_LOAD_MISALIGNED  = 4
  val CAUSE_LOAD_ACCESS      = 5
  val CAUSE_STORE_MISALIGNED = 6
  val CAUSE_STORE_ACCESS     = 7
  val CAUSE_ECALL_U          = 8 // + privilege level: 9 = S, 11 = M
  val CAUSE_FETCH_PAGE       = 12
  val CAUSE_LOAD_PAGE        = 13
  val CAUSE_STORE_PAGE       = 15
}
