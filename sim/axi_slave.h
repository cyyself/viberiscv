// Cycle-level AXI4 slave model with an address-decoding interconnect.
#pragma once
#include <cstdint>
#include <cstdio>
#include <deque>
#include <functional>
#include <vector>

#include "devices.h"

// Signals driven by the DUT (AXI master), sampled each cycle.
struct AxiMasterSignals {
  bool awvalid; uint64_t awaddr; uint32_t awid, awlen, awsize, awburst;
  bool wvalid; uint64_t wdata; uint32_t wstrb; bool wlast;
  bool bready;
  bool arvalid; uint64_t araddr; uint32_t arid, arlen, arsize, arburst;
  bool rready;
};

// Signals driven by the slave model.
struct AxiSlaveSignals {
  bool awready = false, wready = false;
  bool bvalid = false; uint32_t bid = 0, bresp = 0;
  bool arready = false;
  bool rvalid = false; uint32_t rid = 0, rresp = 0; uint64_t rdata = 0; bool rlast = false;
};

class AxiSlave {
 public:
  enum { OKAY = 0, SLVERR = 2, DECERR = 3 };
  using WriteHook = std::function<void(uint64_t addr, uint64_t data, uint8_t strb)>;

  explicit AxiSlave(int latency = 1) : latency_(latency) {}

  void add_device(Device* d) { devs_.push_back(d); }
  void set_write_hook(WriteHook h) { hook_ = std::move(h); }
  void set_verbose(bool v) { verbose_ = v; }

  // Outputs to drive for the current cycle (depend only on internal state).
  AxiSlaveSignals outputs() const {
    AxiSlaveSignals s;
    s.arready = rq_.size() < kMaxOutstanding;
    s.awready = awq_.size() < kMaxOutstanding;
    s.wready = !awq_.empty();
    if (!rq_.empty() && rq_.front().delay == 0) {
      const Burst& b = rq_.front();
      s.rvalid = true;
      s.rid = b.id;
      uint64_t a = beat_addr(b) & ~7ull;
      int resp = access_read(a, s.rdata);
      s.rresp = resp;
      s.rlast = b.beat == b.len;
    }
    if (!bq_.empty() && bq_.front().delay == 0) {
      s.bvalid = true;
      s.bid = bq_.front().id;
      s.bresp = bq_.front().resp;
    }
    return s;
  }

  // Called once per rising clock edge with the master's signals for that edge.
  void tick(const AxiMasterSignals& m) {
    const AxiSlaveSignals s = outputs();

    // Age pending transactions first so that new ones see the full latency.
    for (auto& b : rq_) if (b.delay > 0) b.delay--;
    for (auto& b : bq_) if (b.delay > 0) b.delay--;

    if (s.rvalid && m.rready) {
      Burst& b = rq_.front();
      if (b.beat == b.len) rq_.pop_front(); else b.beat++;
    }
    if (s.bvalid && m.bready) bq_.pop_front();

    if (s.wready && m.wvalid) {
      Burst& b = awq_.front();
      uint64_t a = beat_addr(b) & ~7ull;
      int resp = access_write(a, m.wdata, uint8_t(m.wstrb));
      if (resp != OKAY) b.resp = resp;
      bool last = b.beat == b.len;
      if (last != m.wlast && verbose_)
        fprintf(stderr, "[axi] WLAST mismatch at beat %u of %u\n", b.beat, b.len);
      if (last) {
        bq_.push_back({b.id, 0, 0, 0, 0, 0, latency_, b.resp});
        awq_.pop_front();
      } else {
        b.beat++;
      }
    }
    if (s.awready && m.awvalid)
      awq_.push_back({m.awid, m.awaddr, m.awlen, m.awsize, m.awburst, 0, 0, OKAY});
    if (s.arready && m.arvalid)
      rq_.push_back({m.arid, m.araddr, m.arlen, m.arsize, m.arburst, 0, latency_, OKAY});
  }

  Device* find(uint64_t addr) const {
    for (auto* d : devs_) if (d->contains(addr)) return d;
    return nullptr;
  }

 private:
  static constexpr size_t kMaxOutstanding = 4;
  struct Burst {
    uint32_t id; uint64_t addr; uint32_t len, size, burst, beat; int delay; int resp;
  };

  static uint64_t beat_addr(const Burst& b) {
    uint64_t bytes = 1ull << b.size;
    if (b.burst == 0) return b.addr;  // FIXED
    uint64_t aligned = b.addr & ~(bytes - 1);
    uint64_t a = (b.beat == 0) ? b.addr : aligned + b.beat * bytes;
    if (b.burst == 2) {  // WRAP
      uint64_t total = bytes * (b.len + 1);
      uint64_t lo = b.addr & ~(total - 1);
      a = lo + ((aligned + b.beat * bytes) - lo) % total;
    }
    return a;
  }

  int access_read(uint64_t a, uint64_t& data) const {
    Device* d = find(a);
    if (!d) {
      if (verbose_) fprintf(stderr, "[axi] read from unmapped address 0x%lx\n", (unsigned long)a);
      data = 0;
      return DECERR;
    }
    return d->read64(a - d->base(), data) ? OKAY : SLVERR;
  }

  int access_write(uint64_t a, uint64_t data, uint8_t strb) {
    if (hook_) hook_(a, data, strb);
    Device* d = find(a);
    if (!d) {
      if (verbose_) fprintf(stderr, "[axi] write to unmapped address 0x%lx\n", (unsigned long)a);
      return DECERR;
    }
    return d->write64(a - d->base(), data, strb) ? OKAY : SLVERR;
  }

  int latency_;
  bool verbose_ = true;
  std::vector<Device*> devs_;
  std::deque<Burst> rq_, awq_, bq_;
  WriteHook hook_;
};
