// Minimal runtime: UART output, printf, exit via tohost, and libgcc helpers
// (RV32I has no multiply/divide instructions).
#include <stdarg.h>
#include "lib.h"

extern volatile uint64_t tohost;

#define UART_THR (*(volatile uint8_t*)(UART_BASE + 0))
#define UART_LSR (*(volatile uint8_t*)(UART_BASE + 5))

void putchar_(char c) {
  while (!(UART_LSR & 0x20)) {}
  UART_THR = (uint8_t)c;
}

void puts_(const char* s) {
  while (*s) putchar_(*s++);
}

void exit(int code) {
  tohost = ((uint64_t)code << 1) | 1;
  for (;;) {}
}

unsigned __mulsi3(unsigned a, unsigned b) {
  unsigned r = 0;
  while (b) { if (b & 1) r += a; a <<= 1; b >>= 1; }
  return r;
}

static unsigned udivmod(unsigned n, unsigned d, unsigned* rem) {
  unsigned q = 0, r = 0;
  if (d == 0) { if (rem) *rem = n; return ~0u; }
  for (int i = 31; i >= 0; i--) {
    r = (r << 1) | ((n >> i) & 1);
    if (r >= d) { r -= d; q |= 1u << i; }
  }
  if (rem) *rem = r;
  return q;
}
unsigned __udivsi3(unsigned n, unsigned d) { return udivmod(n, d, 0); }
unsigned __umodsi3(unsigned n, unsigned d) { unsigned r; udivmod(n, d, &r); return r; }
int __divsi3(int n, int d) {
  int neg = (n < 0) ^ (d < 0);
  unsigned q = udivmod(n < 0 ? -(unsigned)n : (unsigned)n, d < 0 ? -(unsigned)d : (unsigned)d, 0);
  return neg ? -(int)q : (int)q;
}
int __modsi3(int n, int d) {
  unsigned r;
  udivmod(n < 0 ? -(unsigned)n : (unsigned)n, d < 0 ? -(unsigned)d : (unsigned)d, &r);
  return n < 0 ? -(int)r : (int)r;
}

static void print_uint(unsigned v, unsigned base, int width, char pad) {
  char buf[12];
  int n = 0;
  do { unsigned d = v % base; buf[n++] = d < 10 ? '0' + d : 'a' + d - 10; v /= base; } while (v);
  while (n < width) { putchar_(pad); width--; }
  while (n) putchar_(buf[--n]);
}

void printf_(const char* fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  for (; *fmt; fmt++) {
    if (*fmt != '%') { putchar_(*fmt); continue; }
    fmt++;
    char pad = ' ';
    int width = 0;
    if (*fmt == '0') { pad = '0'; fmt++; }
    while (*fmt >= '0' && *fmt <= '9') width = width * 10 + (*fmt++ - '0');
    switch (*fmt) {
      case 'd': {
        int v = va_arg(ap, int);
        if (v < 0) { putchar_('-'); v = -v; }
        print_uint((unsigned)v, 10, width, pad);
        break;
      }
      case 'u': print_uint(va_arg(ap, unsigned), 10, width, pad); break;
      case 'x': print_uint(va_arg(ap, unsigned), 16, width, pad); break;
      case 'c': putchar_((char)va_arg(ap, int)); break;
      case 's': puts_(va_arg(ap, const char*)); break;
      case '%': putchar_('%'); break;
      default: putchar_('%'); putchar_(*fmt); break;
    }
  }
  va_end(ap);
}
