// Verilator testbench for RVTop: drives the 64-bit AXI4 master port with a
// slave model that decodes bootrom @0x10000, UART @0x60000000, DRAM @0x80000000.
#include <elf.h>

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <memory>
#include <string>
#include <vector>

#include "VRVTop.h"
#include "verilated.h"
#include "verilated_vcd_c.h"
#include "axi_slave.h"
#include "devices.h"

static const uint64_t BOOTROM_BASE = 0x00010000, BOOTROM_SIZE = 0x10000;
static const uint64_t UART_BASE = 0x60000000;
static const uint64_t DRAM_BASE = 0x80000000;

// Built-in boot ROM, used when --bootrom is not given:
//   csrr a0, mhartid ; lui t0, 0x80000 ; jr t0
static const uint32_t kDefaultBootrom[] = {0xf1402573, 0x800002b7, 0x00028067};

struct Options {
  std::string program;
  std::string bootrom;
  std::string vcd;
  uint64_t max_cycles = 50000000;
  uint64_t dram_size = 64ull << 20;
  int latency = 1;
  bool trace = false;
  bool quiet = false;
  long long tohost = -1;
};

static void usage(const char* argv0) {
  fprintf(stderr,
          "Usage: %s [options] <program.elf | program.bin>\n"
          "  --bootrom <file>    raw binary placed at 0x%lx (default: built-in jump to DRAM)\n"
          "  --max-cycles <n>    stop after n cycles (default 50M)\n"
          "  --dram-size <MiB>   DRAM size in MiB (default 64)\n"
          "  --latency <n>       AXI slave response latency in cycles (default 1)\n"
          "  --tohost <addr>     tohost address (default: ELF symbol 'tohost')\n"
          "  --vcd <file>        dump waveform\n"
          "  --trace             print every committed instruction\n"
          "  --quiet             suppress the end-of-run summary\n",
          argv0, (unsigned long)BOOTROM_BASE);
  exit(2);
}

static bool read_file(const std::string& path, std::vector<uint8_t>& buf) {
  std::ifstream f(path, std::ios::binary);
  if (!f) return false;
  buf.assign(std::istreambuf_iterator<char>(f), {});
  return true;
}

static Memory* mem_for(const std::vector<Memory*>& mems, uint64_t addr) {
  for (auto* m : mems) if (m->contains(addr)) return m;
  return nullptr;
}

// Loads PT_LOAD segments of a 32-bit RISC-V ELF; returns the 'tohost' symbol address or -1.
static long long load_elf(const std::vector<uint8_t>& img, const std::vector<Memory*>& mems) {
  auto* eh = reinterpret_cast<const Elf32_Ehdr*>(img.data());
  if (img.size() < sizeof(Elf32_Ehdr) || eh->e_ident[EI_CLASS] != ELFCLASS32 || eh->e_machine != EM_RISCV) {
    fprintf(stderr, "error: not a 32-bit RISC-V ELF\n");
    exit(2);
  }
  for (int i = 0; i < eh->e_phnum; i++) {
    auto* ph = reinterpret_cast<const Elf32_Phdr*>(img.data() + eh->e_phoff + i * eh->e_phentsize);
    if (ph->p_type != PT_LOAD || ph->p_memsz == 0) continue;
    Memory* m = mem_for(mems, ph->p_paddr);
    if (!m) {
      fprintf(stderr, "error: segment at 0x%x is outside memory\n", ph->p_paddr);
      exit(2);
    }
    std::vector<uint8_t> seg(ph->p_memsz, 0);
    std::memcpy(seg.data(), img.data() + ph->p_offset, ph->p_filesz);
    if (!m->load(ph->p_paddr, seg.data(), seg.size())) {
      fprintf(stderr, "error: segment at 0x%x (0x%x bytes) does not fit\n", ph->p_paddr, ph->p_memsz);
      exit(2);
    }
  }
  long long tohost = -1;
  for (int i = 0; i < eh->e_shnum; i++) {
    auto* sh = reinterpret_cast<const Elf32_Shdr*>(img.data() + eh->e_shoff + i * eh->e_shentsize);
    if (sh->sh_type != SHT_SYMTAB) continue;
    auto* strtab = reinterpret_cast<const Elf32_Shdr*>(img.data() + eh->e_shoff + sh->sh_link * eh->e_shentsize);
    const char* names = reinterpret_cast<const char*>(img.data() + strtab->sh_offset);
    for (size_t j = 0; j < sh->sh_size / sizeof(Elf32_Sym); j++) {
      auto* sym = reinterpret_cast<const Elf32_Sym*>(img.data() + sh->sh_offset + j * sizeof(Elf32_Sym));
      if (std::strcmp(names + sym->st_name, "tohost") == 0) tohost = sym->st_value;
    }
  }
  return tohost;
}

int main(int argc, char** argv) {
  Options opt;
  for (int i = 1; i < argc; i++) {
    std::string a = argv[i];
    auto next = [&]() -> std::string { if (i + 1 >= argc) usage(argv[0]); return argv[++i]; };
    if (a == "--bootrom") opt.bootrom = next();
    else if (a == "--max-cycles") opt.max_cycles = std::stoull(next());
    else if (a == "--dram-size") opt.dram_size = std::stoull(next()) << 20;
    else if (a == "--latency") opt.latency = std::stoi(next());
    else if (a == "--tohost") opt.tohost = std::stoll(next(), nullptr, 0);
    else if (a == "--vcd") opt.vcd = next();
    else if (a == "--trace") opt.trace = true;
    else if (a == "--quiet") opt.quiet = true;
    else if (a.rfind("+", 0) == 0) continue;  // verilator plusargs
    else if (a[0] == '-') usage(argv[0]);
    else opt.program = a;
  }
  if (opt.program.empty()) usage(argv[0]);

  // ---------------------------------------------------------------- memory map
  Memory bootrom("bootrom", BOOTROM_BASE, BOOTROM_SIZE, false);
  Memory dram("dram", DRAM_BASE, opt.dram_size, true);
  Uart16550 uart(UART_BASE, stdout);
  std::vector<Memory*> mems = {&bootrom, &dram};

  if (opt.bootrom.empty()) {
    bootrom.load(BOOTROM_BASE, reinterpret_cast<const uint8_t*>(kDefaultBootrom), sizeof(kDefaultBootrom));
  } else {
    std::vector<uint8_t> img;
    if (!read_file(opt.bootrom, img) || !bootrom.load(BOOTROM_BASE, img.data(), img.size())) {
      fprintf(stderr, "error: cannot load bootrom '%s'\n", opt.bootrom.c_str());
      return 2;
    }
  }

  std::vector<uint8_t> img;
  if (!read_file(opt.program, img)) {
    fprintf(stderr, "error: cannot read '%s': %s\n", opt.program.c_str(), strerror(errno));
    return 2;
  }
  long long tohost = opt.tohost;
  if (img.size() >= 4 && std::memcmp(img.data(), ELFMAG, SELFMAG) == 0) {
    long long sym = load_elf(img, mems);
    if (tohost < 0) tohost = sym;
  } else if (!dram.load(DRAM_BASE, img.data(), img.size())) {
    fprintf(stderr, "error: binary does not fit in DRAM\n");
    return 2;
  }

  AxiSlave axi(opt.latency);
  axi.add_device(&bootrom);
  axi.add_device(&uart);
  axi.add_device(&dram);

  bool done = false;
  uint32_t exit_code = 0;
  if (tohost >= 0) {
    axi.set_write_hook([&](uint64_t addr, uint64_t data, uint8_t strb) {
      if ((addr & ~7ull) != (uint64_t(tohost) & ~7ull)) return;
      int sh = (tohost & 4) ? 32 : 0;
      if (!((strb >> (sh / 8)) & 0xf)) return;
      uint32_t v = uint32_t(data >> sh);
      if (v & 1) { done = true; exit_code = v >> 1; }
    });
  }

  // ---------------------------------------------------------------- DUT
  auto ctx = std::make_unique<VerilatedContext>();
  ctx->commandArgs(argc, argv);
  auto top = std::make_unique<VRVTop>(ctx.get());
  std::unique_ptr<VerilatedVcdC> vcd;
  if (!opt.vcd.empty()) {
    ctx->traceEverOn(true);
    vcd = std::make_unique<VerilatedVcdC>();
    top->trace(vcd.get(), 99);
    vcd->open(opt.vcd.c_str());
  }

  auto drive = [&](const AxiSlaveSignals& s) {
    top->m_axi_awready = s.awready;
    top->m_axi_wready = s.wready;
    top->m_axi_bvalid = s.bvalid;
    top->m_axi_bid = s.bid;
    top->m_axi_bresp = s.bresp;
    top->m_axi_arready = s.arready;
    top->m_axi_rvalid = s.rvalid;
    top->m_axi_rid = s.rid;
    top->m_axi_rdata = s.rdata;
    top->m_axi_rresp = s.rresp;
    top->m_axi_rlast = s.rlast;
  };
  auto sample = [&]() {
    AxiMasterSignals m;
    m.awvalid = top->m_axi_awvalid; m.awaddr = top->m_axi_awaddr; m.awid = top->m_axi_awid;
    m.awlen = top->m_axi_awlen; m.awsize = top->m_axi_awsize; m.awburst = top->m_axi_awburst;
    m.wvalid = top->m_axi_wvalid; m.wdata = top->m_axi_wdata; m.wstrb = top->m_axi_wstrb;
    m.wlast = top->m_axi_wlast; m.bready = top->m_axi_bready;
    m.arvalid = top->m_axi_arvalid; m.araddr = top->m_axi_araddr; m.arid = top->m_axi_arid;
    m.arlen = top->m_axi_arlen; m.arsize = top->m_axi_arsize; m.arburst = top->m_axi_arburst;
    m.rready = top->m_axi_rready;
    return m;
  };

  uint64_t t = 0;
  auto half = [&](int clk) {
    top->clock = clk;
    top->eval();
    if (vcd) vcd->dump(t);
    t++;
  };

  // Reset
  drive(AxiSlaveSignals{});
  top->reset = 1;
  for (int i = 0; i < 10; i++) { half(0); half(1); }
  top->reset = 0;

  uint64_t cycle = 0, instret = 0;
  while (!done && cycle < opt.max_cycles && !ctx->gotFinish()) {
    // Low phase: present slave outputs, let combinational logic settle, sample master.
    drive(axi.outputs());
    half(0);
    AxiMasterSignals m = sample();

    if (top->commit_valid) {
      instret++;
      if (opt.trace) {
        fprintf(stderr, "%10lu  pc=%08x inst=%08x", (unsigned long)cycle, top->commit_pc, top->commit_inst);
        if (top->commit_wen) fprintf(stderr, "  x%-2d <= %08x", top->commit_rd, top->commit_wdata);
        fprintf(stderr, "\n");
      }
    }

    // Rising edge: DUT and slave model both consume this cycle's handshakes.
    half(1);
    axi.tick(m);
    cycle++;
  }
  // Let any final response reach the trace before closing.
  if (vcd) { half(0); vcd->close(); }
  top->final();

  int rc;
  if (done) {
    rc = exit_code == 0 ? 0 : 1;
    if (!opt.quiet) {
      if (exit_code == 0) fprintf(stderr, "PASS");
      else fprintf(stderr, "FAIL (tohost exit code %u)", exit_code);
    }
  } else {
    rc = 3;
    if (!opt.quiet) fprintf(stderr, "TIMEOUT");
  }
  if (!opt.quiet)
    fprintf(stderr, "  cycles=%lu instret=%lu IPC=%.3f\n", (unsigned long)cycle, (unsigned long)instret,
            cycle ? double(instret) / cycle : 0.0);
  return rc;
}
