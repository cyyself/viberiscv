# Bare-metal startup for programs linked at DRAM (0x80000000).
  .section .text.init
  .globl _start
_start:
  la   t0, trap_entry
  csrw mtvec, t0
  la   sp, _stack_top
  # clear .bss
  la   t0, _bss_start
  la   t1, _bss_end
1:
  bgeu t0, t1, 2f
  sw   zero, 0(t0)
  addi t0, t0, 4
  j    1b
2:
  call main
  call exit
3:
  j 3b

  .align 2
trap_entry:
  # Any trap is fatal here: report mcause as a failure code.
  csrr a0, mcause
  addi a0, a0, 0x100
  call exit
  j trap_entry

  .section .tohost, "aw", @progbits
  .align 6
  .globl tohost
tohost: .dword 0
  .align 6
  .globl fromhost
fromhost: .dword 0
