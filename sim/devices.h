// Memory-mapped devices for the AXI slave model.
#pragma once
#include <cstdint>
#include <cstdio>
#include <memory>
#include <string>
#include <vector>

class Device {
 public:
  Device(std::string name, uint64_t base, uint64_t size) : name_(std::move(name)), base_(base), size_(size) {}
  virtual ~Device() = default;
  // `addr` is an offset from base, aligned to 8 bytes. Returns false on error (-> SLVERR).
  virtual bool read64(uint64_t off, uint64_t& data) = 0;
  virtual bool write64(uint64_t off, uint64_t data, uint8_t strb) = 0;
  bool contains(uint64_t addr) const { return addr >= base_ && addr - base_ < size_; }
  uint64_t base() const { return base_; }
  uint64_t size() const { return size_; }
  const std::string& name() const { return name_; }

 private:
  std::string name_;
  uint64_t base_, size_;
};

// Plain RAM/ROM backed by a byte array.
class Memory : public Device {
 public:
  Memory(std::string name, uint64_t base, uint64_t size, bool writable)
      : Device(std::move(name), base, size), mem_(size, 0), writable_(writable) {}
  bool read64(uint64_t off, uint64_t& data) override {
    data = 0;
    for (int i = 0; i < 8; i++) data |= uint64_t(mem_[off + i]) << (8 * i);
    return true;
  }
  bool write64(uint64_t off, uint64_t data, uint8_t strb) override {
    if (!writable_) return false;
    for (int i = 0; i < 8; i++)
      if (strb & (1u << i)) mem_[off + i] = uint8_t(data >> (8 * i));
    return true;
  }
  // Backdoor access used by the loaders (ignores write protection).
  bool load(uint64_t addr, const uint8_t* src, size_t len) {
    if (addr < base() || addr - base() + len > size()) return false;
    std::copy(src, src + len, mem_.begin() + (addr - base()));
    return true;
  }
  uint8_t* data() { return mem_.data(); }

 private:
  std::vector<uint8_t> mem_;
  bool writable_;
};

// Minimal 16550-compatible UART (register shift 0, byte registers).
//   +0 THR (write: transmit) / RBR (read: receive, always 0)
//   +5 LSR: bit5 THRE, bit6 TEMT always set -> transmitter always ready
class Uart16550 : public Device {
 public:
  Uart16550(uint64_t base, FILE* out) : Device("uart", base, 0x1000), out_(out) {}
  bool read64(uint64_t off, uint64_t& data) override {
    data = 0;
    for (int i = 0; i < 8; i++) data |= uint64_t(reg_read(off + i)) << (8 * i);
    return true;
  }
  bool write64(uint64_t off, uint64_t data, uint8_t strb) override {
    for (int i = 0; i < 8; i++)
      if (strb & (1u << i)) reg_write(off + i, uint8_t(data >> (8 * i)));
    return true;
  }

 private:
  uint8_t reg_read(uint64_t r) {
    switch (r) {
      case 3: return lcr_;
      case 5: return 0x60;
      case 7: return scr_;
      default: return 0;
    }
  }
  void reg_write(uint64_t r, uint8_t v) {
    switch (r) {
      case 0:
        if (!(lcr_ & 0x80)) { fputc(v, out_); fflush(out_); }
        break;
      case 3: lcr_ = v; break;
      case 7: scr_ = v; break;
      default: break;
    }
  }
  FILE* out_;
  uint8_t lcr_ = 0, scr_ = 0;
};
