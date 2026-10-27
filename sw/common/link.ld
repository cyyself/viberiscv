OUTPUT_ARCH(riscv)
ENTRY(_start)
MEMORY { DRAM (rwx) : ORIGIN = 0x80000000, LENGTH = 64M }
SECTIONS
{
  .text : { *(.text.init) *(.text*) } > DRAM
  .tohost ALIGN(0x1000) : { *(.tohost) } > DRAM
  .rodata : { *(.rodata*) *(.srodata*) } > DRAM
  .data : { *(.data*) *(.sdata*) } > DRAM
  .bss : { _bss_start = .; *(.bss*) *(.sbss*) *(COMMON) . = ALIGN(4); _bss_end = .; } > DRAM
  . = ALIGN(16);
  . += 0x10000;
  _stack_top = .;
}
