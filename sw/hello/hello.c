#include "lib.h"

static unsigned fib(unsigned n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }

static int sieve_count(int n) {
  static char composite[1000];
  int count = 0;
  for (int i = 2; i < n; i++) {
    if (composite[i]) continue;
    count++;
    for (int j = i * i; j < n; j += i) composite[j] = 1;
  }
  return count;
}

int main(void) {
  uint32_t c0 = read_mcycle(), i0 = read_minstret();
  printf_("Hello from viberiscv (RV32I, 5-stage, AXI4-64)!\n");
  for (unsigned i = 0; i <= 15; i++) printf_("fib(%u) = %u\n", i, fib(i));
  int primes = sieve_count(1000);
  printf_("primes below 1000: %d\n", primes);
  printf_("signed: %d / %d = %d rem %d\n", -100, 7, -100 / 7, -100 % 7);
  uint32_t c1 = read_mcycle(), i1 = read_minstret();
  printf_("cycles=%u instret=%u\n", c1 - c0, i1 - i0);
  return (primes == 168 && fib(15) == 610) ? 0 : 1;
}
