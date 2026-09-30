# Trane Link Zone Panel (ZZON2PNLA200ZA) — CANopen Protocol Map

Reverse-engineered from a verified firmware dump of the zone panel MCU.
Target end goal: **emulate this panel with an ESP32** so a DIY multi-zone
integration can join the Trane ComfortLink II / Link (SC360 / UX360) bus.

## Hardware

- **MCU:** STM32F303xE (Cortex-M4F, device ID 0x446, 512 KB flash @ `0x08000000`), potted.
- **CAN:** on-chip bxCAN peripheral `CAN1` @ `0x40006400`, standard 11-bit IDs.
- **RTOS:** FreeRTOS.
- **Dump:** `dumps/zone-panel-flash.bin` (two SWD reads identical, sha256 `d53a2eb9…`). RDP level 0.
- Firmware occupies ~`0x08000000`–`0x0801F000` (~124 KB); rest zero-filled.

## Big picture: it's CANopen, IDs are assigned — not hardcoded

There is **no static CAN-ID table** and **no `switch(can_id)`** anywhere in the
firmware (all 1005 functions decompiled and scanned; zero ID comparisons).
Instead the panel is a **CANopen node**: every CAN ID is a COB-ID derived as
`functional_base + node_id`, and reception is done with the STM32 hardware
acceptance filters (dispatch by filter-match index, not software ID compares).

This is why the installation guide's auto-discovery works
(18-HD99D1-1A-EN §8.3): up to two panels, first to answer a Link message
becomes **Zone Panel 1** (zones 1–3), the other **Zone Panel 2** (zones 4–6).
"Zone Panel 1 vs 2" is simply two different assigned node IDs.

## LIVE CAPTURE CONFIRMED (2026-09-29, `dumps/live-bus-capture-*.log`)

Panel installed on the live bus with the SC360 present. Full identity from its
CAN console (ID **0x6F0**, plain ASCII text): model `ZZON2KITA200ZAA`,
serial `2408DPCA` / `0x96B4`, bootloader `ZP v1.0`, app build `05.01.00.230514`.

**Discovery = CANopen LSS Fastscan** (CiA DS-305). LSS COB-IDs: master→slave
**0x7E5**, slave→master **0x7E4**. Fastscan frame (cmd 0x51) layout:
`[0]=0x51  [1..4]=IDNumber(LE)  [5]=BitCheck  [6]=LSS_Sub  [7]=LSS_Next`.
The master walks the node's 4-part LSS address (sub0=vendor-id, sub1=product-code,
sub2=revision, sub3=serial). Full clean capture (dumps/live-lss-handshake-*.log):
```
7E5 51 00000000 80 0000   probe: any unconfigured node?  (BitCheck 0x80)
7E4 4F ...                 → node answers yes
7E5 51 00000001 00 0001   sub0 vendor-id identified → next field
7E5 51 00000008 00 0102   sub1 product-code
7E5 51 00000000 00 0203   sub2 revision
7E5 51 B8FA8C86 00 0304   sub3 serial = 0xB8FA8C86
7E4 4F ...                 → fully identified
7E5 11 05 01 00000000     LSS Configure Node-ID  →  node ID = 0x05
7E4 11 00 ...              panel acks (0x11, err 0x00)
7E5 04 00 ...              LSS Switch-State global (activate)
705 7F                     node 5 → PRE-OPERATIONAL
705 05                     node 5 → OPERATIONAL (heartbeat q5s)
```
Panel LSS identity = {vendor-id, product-code, revision, **serial 0xB8FA8C86**};
first panel is assigned **node 5** deterministically. After assignment the SC360
keeps sending `7E5 51 00000000 80 0000` every ~5 s to scan for a *second* panel —
the guide's first-come/first-serve loop.

**Panel then boots as node 5:** `705 00` (NMT boot-up) → `705 7F` (pre-op) →
`705 05` (operational) heartbeat every 5 s. And every predicted TPDO transmits:

| COB-ID | Live payload (no zones configured) | Meaning |
|--------|-----------------------------------|---------|
| 0x2C0 | `0000C6C2 0000C6C2` | 2× float −99.0 (sentinel/no-data) |
| 0x2C4 | `0000C6C2` | float −99.0 |
| 0x2C8 | `00FE00FE00FE00` | per-zone status, all invalid |
| 0x2CC | `07000000` | **0x07 = zones 1-3 bitmask (Zone Panel 1)** |
| 0x2D0 | `0100010001000003` | 3 zone entries + status byte |

So node ID → COB-IDs is confirmed: heartbeat 0x705 = 0x700+5, and this build's
first-panel role transmits the fixed 0x2C0–0x2D0 set. Zone Panel 2 would get a
different node ID via LSS and (per firmware) report zones 4-6.

### Emulation handshake (what the ESP must do)
1. Boot unconfigured; answer the SC360's **LSS Fastscan** (0x51 on 0x7E5) with
   0x7E4 responses matching the ESP's own LSS address, bit by bit.
2. Accept **LSS Configure Node-ID** (0x11) and **Switch-State** (0x04).
3. Send NMT boot-up `700+N / 0x00`, go pre-op then operational; heartbeat
   `700+N` = 0x05 every 5 s.
4. Transmit TPDOs 0x2C0–0x2D0 with the sizes/timing below and real zone data.

## Node-ID assignment (the key to emulation)

- The node ID lives in a control struct at offset `+0x18`. It **starts at 0**
  (CANopen "unconfigured").
- `FUN_08017308` reads it back; on boot it is 0 until the SC360 assigns one
  during commissioning.
- Boot sequence:
  1. `FUN_0800dd24` brings the stack up first as **node 0**
     (`FUN_08016440(0x32, 0, 5000)`).
  2. After assignment, `FUN_0800d104` re-initializes with the real node ID
     (`FUN_08016440(period, node_id, 5000)`) and installs the full PDO map.

**CANopen init** `FUN_08016440(hb_period, node_id, …)` sets up the standard
node services from `node_id = N`:

| Service        | COB-ID   |
|----------------|----------|
| Heartbeat (TX) | `N + 0x700` |
| SDO server TX  | `N + 0x580` |
| SDO server RX  | `N + 0x600` |

Plus fixed accepted IDs: `0x7E5`, `0x100`, `0x000` (NMT).

## PDO map (installed by `FUN_0800d104` after assignment)

### TPDOs — messages the panel TRANSMITS
Registered via `FUN_08016788(index, cob_id, event_ms, inhibit_ms, 0xFF, dlc, img_offset)`:

| COB-ID | DLC | Timing (event/inhibit ms) | Notes |
|--------|-----|---------------------------|-------|
| 0x2C0  | 8   | 5000 / 2000 | |
| 0x2C4  | 4   | 5000 / 2000 | |
| 0x2C8  | 7   | 1000 / 1000 | fastest — likely live zone/damper status |
| 0x2CC  | 4   | 5000 / 1000 | |
| 0x2D0  | 8   | 5000 / 10   | |
| 0x540  | 2   | 0 / 10      | |
| 0x560  | 3   | 0 / 10      | |

> These COB-IDs (0x2C0–0x2D0) were **absent** from the pre-panel bus sniff,
> confirming they are the panel's own transmissions. They are the first thing
> to look for when the real panel is installed.

### RPDOs / consumed objects — messages the panel RECEIVES
Registered via `FUN_08016564(index, cob_id, size_bytes, img_offset)`:

| COB-ID(s)     | Size | Notes |
|---------------|------|-------|
| 0x250         | 4B   | |
| 0x53E         | 6B   | |
| 0x53F … 0x55F | 2B ea (~33 objects) | large block — likely per-zone sensor/setpoint values |
| 0x240         | 3B   | |

### SDO clients
The panel opens SDO client channels to other nodes:

| Peer node | SDO TX (to peer) | SDO RX (from peer) |
|-----------|------------------|--------------------|
| 0x22      | 0x622            | 0x5A2              |
| 0x2A      | 0x62A            | 0x5AA              |
| 0x6A      | 0x66A            | 0x5EA              |

## Cross-check against the live bus sniff (no panel installed yet)

The earlier sniff of the running system (SC360 + UX360 + AHU + heat pump)
showed CANopen-consistent traffic that corroborates this model:
- Heartbeats `0x701`–`0x704` = `0x700 + node` for nodes 1–4.
- SDO pairs `0x601/0x581` (node 1), `0x621/0x5A1` (node 0x21).
- Fixed `0x7E5` present (matches the panel's fixed accepted ID).

## Emulation plan (ESP32 + CAN transceiver)

1. **Bench first (zero risk):** run the real panel on an isolated 2-node CAN
   bus (panel + ESP/analyzer, 120 Ω at each end). Capture its boot-up as
   node 0 — NMT boot-up message, node-0 heartbeat, and whatever it emits while
   waiting for a master. Confirms default COB-IDs with no effect on the HVAC.
2. **Live capture:** put the analyzer on the real bus with the panel to record
   the SC360's assignment handshake (how node 0 becomes Zone Panel 1) and the
   real assigned node ID → fixes the exact heartbeat/SDO COB-IDs.
3. **Emulator:** ESP32 CANopen node that boots at node 0, joins the discovery
   handshake, accepts the assigned node ID, then produces the TPDOs above with
   plausible zone data and answers SDO reads on its object dictionary.

## Ghidra / analysis artifacts

Headless analysis (no GUI/plugin — the GhidraMCP plugin was built for 12.1.2
but brew ships 12.1.4; `analyzeHeadless` with JDK 21 was used instead):

- `ghidra/scripts/DumpCanRouter.java` — find RX handlers / scan all funcs for ID constants
- `ghidra/scripts/DumpCanOpen.java` — find COB-ID base usage, TX-id construction, filter writes
- `ghidra/scripts/DumpFuncs.java` — dump specific functions + their callers
- `ghidra/canopen.txt`, `ghidra/nodeid.txt`, `ghidra/assign.txt` — decompiler output

Re-run example:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
/opt/homebrew/Cellar/ghidra/12.1.4/libexec/support/analyzeHeadless \
  ghidra/proj zp -import dumps/zone-panel-flash.bin \
  -processor "ARM:LE:32:Cortex" -loader BinaryLoader -loader-baseAddr 0x08000000 \
  -scriptPath ghidra/scripts -postScript DumpFuncs.java -deleteProject
```

## Key firmware addresses

| Address | Role |
|---------|------|
| `FUN_08016440` | CANopen stack init (node-ID → heartbeat/SDO COB-IDs) |
| `FUN_08017308` | read assigned node ID (struct `+0x18`, 0 = unconfigured) |
| `FUN_0800dd24` | boot: first init as node 0 |
| `FUN_0800d104` | re-init with assigned node + install full PDO map |
| `FUN_08016788` | register a TPDO |
| `FUN_08016564` | register an RPDO / consumed object |
| `FUN_080025c8` / `FUN_0800b0e0` | bxCAN RX FIFO IRQ handlers (call registered callback with `id, dlc, data*`) |
