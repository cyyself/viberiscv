# Boot ROM, mapped at 0x10000. Jumps to the start of DRAM with a0 = hartid.
  .section .text
  .globl _start
_start:
  csrr a0, mhartid
  li   t0, 0x80000000
  jr   t0
