#ifndef LIB_H
#define LIB_H
#include <stdint.h>

#define UART_BASE 0x60000000u

void putchar_(char c);
void puts_(const char* s);
void printf_(const char* fmt, ...);
void exit(int code) __attribute__((noreturn));

static inline uint32_t read_mcycle(void) { uint32_t v; asm volatile("csrr %0, mcycle" : "=r"(v)); return v; }
static inline uint32_t read_minstret(void) { uint32_t v; asm volatile("csrr %0, minstret" : "=r"(v)); return v; }

#endif
