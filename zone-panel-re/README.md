# Trane Link Zone Panel — reverse engineering

Reverse-engineering the **Trane Link Zone Panel (ZZON2PNLA200ZA / ZZON2KITA200Z)**
so it can be **emulated with an ESP32** and integrated into a DIY Home Assistant
setup on the Trane ComfortLink II / Link (SC360 + UX360) CAN bus.

This lives inside the [`esphome-trane`](..) repo — the ESP32 that monitors this
HVAC bus. This `zone-panel-re/` subfolder holds the zone-panel reverse
engineering; the CAN sniffer used for live capture is `../trane-can-sniffer.yaml`.

## TL;DR of the findings

- Panel MCU is an **STM32F303xE** (Cortex-M4F, 512 KB flash), unprotected (RDP 0).
- Full firmware dumped over SWD and analyzed in Ghidra (headless).
- The panel is a **CANopen node**. It has **no hardcoded CAN IDs** — every COB-ID
  is `base + node_id`, and the node ID is **assigned at commissioning** by the
  SC360 via **CANopen LSS Fastscan** (first-come/first-serve = Zone Panel 1 vs 2).
- The full boot → LSS assignment → operational + PDO map is captured and decoded.

**→ See [CANOPEN_MAP.md](CANOPEN_MAP.md) for the complete protocol map and the
ESP32 emulation spec.**

## Repo layout

> `dumps/` (firmware image + raw CAN captures) is kept **locally only** — it's
> git-ignored, not uploaded. The findings derived from it live in `CANOPEN_MAP.md`
> and `ghidra/`.

| Path | What |
|------|------|
| `CANOPEN_MAP.md` | The findings: CANopen protocol map + emulation blueprint |
| `dumps/zone-panel-flash.bin` | Verified 512 KB firmware image (two SWD reads, matching sha256) |
| `dumps/live-lss-handshake-*.log` | Clean live capture of boot + LSS node-ID assignment |
| `dumps/live-bus-capture-*.log` | Live bus capture with the panel operating |
| `ghidra/scripts/*.java` | Headless Ghidra analysis scripts |
| `ghidra/*.txt` | Decompiler output (CAN router, CANopen init, node-id path) |
| `identify.sh` / `dump.sh` | pyOCD SWD identify / full-flash dump |
| `powerup_catch.py` / `catch_and_dump.py` | SWD power-up catch-and-halt (used before RST was wired) |
| `pyocd.yaml` | pyOCD config (under-reset connect, `auto_unlock=false`) |

## Reproducing

### SWD dump (Raspberry Pi Pico running debugprobe firmware)
Wiring: Pico GP2=CLK, GP3=SWD, GP1=RST, GND. Panel powered from a **separate**
24 VAC transformer. **Never** accept an unlock/erase prompt — Trane firmware is
not recoverable.
```bash
python3 -m venv .venv && .venv/bin/pip install pyocd
./dump.sh 0x08000000 0x80000      # connects under hardware reset, reads flash twice, compares
```

### Ghidra headless analysis (Ghidra 12.1.4 + JDK 21)
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
/opt/homebrew/Cellar/ghidra/12.1.4/libexec/support/analyzeHeadless \
  ghidra/proj zp -import dumps/zone-panel-flash.bin \
  -processor "ARM:LE:32:Cortex" -loader BinaryLoader -loader-baseAddr 0x08000000 \
  -scriptPath ghidra/scripts -postScript DumpCanOpen.java -deleteProject
```

### Live CAN capture
Sniffer firmware is in the repo root (`../trane-can-sniffer.yaml`, 50 kbps,
candump-style logging). The main `../esphome-trane.yaml` firmware at
`canbus: VERBOSE` also logs raw frames (`ID:xxx …` format).
