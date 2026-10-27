# Top-level build: Chisel -> SystemVerilog -> Verilator simulator and software.

CROSS     ?= riscv64-linux-gnu-
CC        := $(CROSS)gcc
OBJCOPY   := $(CROSS)objcopy
OBJDUMP   := $(CROSS)objdump
MILL      ?= mill
VERILATOR ?= verilator

BUILD     := build
GEN       := generated
OBJ_DIR   := $(BUILD)/obj_dir
SIM       := $(BUILD)/VRVTop

ARCH      := -march=rv32i_zicsr_zifencei_zba_zbb_zbs -mabi=ilp32
SW_CFLAGS := $(ARCH) -O2 -g -static -nostdlib -nostartfiles -ffreestanding -fno-builtin \
             -fno-pie -no-pie -mcmodel=medany -Wall -Isw/common

SCALA_SRCS := $(shell find src -name '*.scala')
SIM_SRCS   := $(wildcard sim/*.cpp)
SIM_HDRS   := $(wildcard sim/*.h)

.PHONY: all verilog sim sw bootrom hello run-hello clean

all: sim sw

# ---------------------------------------------------------------- hardware
verilog: $(GEN)/.stamp
$(GEN)/.stamp: $(SCALA_SRCS) build.sc
	$(MILL) -i rv.runMain rv.Main
	@touch $@

sim: $(SIM)
$(SIM): $(GEN)/.stamp $(SIM_SRCS) $(SIM_HDRS)
	@mkdir -p $(OBJ_DIR)
	$(VERILATOR) --cc --exe --build -j 0 -O3 --trace --x-assign fast --x-initial fast \
	  -Wno-fatal --top-module RVTop -Mdir $(OBJ_DIR) \
	  -CFLAGS "-O2 -std=c++17 -I$(abspath sim)" \
	  $(addprefix $(GEN)/,$(shell cat $(GEN)/filelist.f)) $(abspath $(SIM_SRCS)) -o VRVTop
	@cp $(OBJ_DIR)/VRVTop $@

# ---------------------------------------------------------------- software
sw: bootrom hello

bootrom: $(BUILD)/sw/bootrom.bin
$(BUILD)/sw/bootrom.elf: sw/bootrom/bootrom.S sw/bootrom/link.ld
	@mkdir -p $(dir $@)
	$(CC) $(SW_CFLAGS) -T sw/bootrom/link.ld $< -o $@
$(BUILD)/sw/%.bin: $(BUILD)/sw/%.elf
	$(OBJCOPY) -O binary $< $@

hello: $(BUILD)/sw/hello.elf
$(BUILD)/sw/hello.elf: sw/hello/hello.c sw/common/start.S sw/common/lib.c sw/common/lib.h sw/common/link.ld
	@mkdir -p $(dir $@)
	$(CC) $(SW_CFLAGS) -T sw/common/link.ld sw/common/start.S sw/common/lib.c sw/hello/hello.c -o $@
	$(OBJDUMP) -d $@ > $(@:.elf=.dump)

run-hello: $(SIM) $(BUILD)/sw/hello.elf $(BUILD)/sw/bootrom.bin
	$(SIM) --bootrom $(BUILD)/sw/bootrom.bin $(BUILD)/sw/hello.elf

clean:
	rm -rf $(BUILD) $(GEN) out
