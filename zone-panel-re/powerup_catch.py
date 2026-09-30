"""Poll the SWD port while the panel is power-cycled and halt the core the instant it answers.

The panel firmware disables SWD shortly after boot, so the halt is issued with raw DP/AP
writes on the same connection instead of reconnecting. VC_CORERESET is also set so a
watchdog or other system reset halts at the reset vector instead of re-running firmware.
Only debug registers are written; flash is never touched.
"""
import sys
import time

from pyocd.core import exceptions
from pyocd.core.helpers import ConnectHelper
from pyocd.coresight.dap import DPConnector
from pyocd.probe.debug_probe import DebugProbe

DURATION_S = float(sys.argv[1]) if len(sys.argv) > 1 else 90

DP_ABORT, DP_CTRL_STAT, DP_SELECT = 0x0, 0x4, 0x8
AP_CSW, AP_TAR, AP_DRW = 0x00, 0x04, 0x0C
DHCSR, DEMCR, CPUID = 0xE000EDF0, 0xE000EDFC, 0xE000ED00

session = ConnectHelper.session_with_chosen_probe(
    blocking=False, return_first=True, auto_open=False,
    options={"auto_unlock": False, "target_override": "cortex_m"})
if session is None:
    sys.exit("No debug probe found")

probe = session.probe
probe.open()
probe.set_clock(1_000_000)
probe.connect(DebugProbe.Protocol.SWD)


def mem_read(addr):
    probe.write_ap(AP_TAR, addr)
    return probe.read_ap(AP_DRW)


def mem_write(addr, value):
    probe.write_ap(AP_TAR, addr)
    probe.write_ap(AP_DRW, value)


def halt_now():
    probe.write_dp(DP_ABORT, 0x1E)
    probe.write_dp(DP_CTRL_STAT, 0x50000000)
    for _ in range(20):
        if probe.read_dp(DP_CTRL_STAT) & 0xA0000000 == 0xA0000000:
            break
    else:
        raise exceptions.TransferError("debug power-up not acknowledged")
    probe.write_dp(DP_SELECT, 0)
    probe.write_ap(AP_CSW, (probe.read_ap(AP_CSW) & ~0x37) | 0x02)
    mem_write(DHCSR, 0xA05F0003)
    mem_write(DEMCR, mem_read(DEMCR) | 0x1)
    return mem_read(DHCSR)


def guarded_read(addr):
    try:
        return mem_read(addr)
    except exceptions.TransferError:
        probe.write_dp(DP_ABORT, 0x1E)
        return None


print(f"Polling for {DURATION_S:.0f}s - power-cycle the panel now", flush=True)
deadline = time.monotonic() + DURATION_S
next_report = time.monotonic() + 2
attempts = 0
dpidr = dhcsr = None
halt_ms = 0.0
while time.monotonic() < deadline:
    attempts += 1
    try:
        conn = DPConnector(probe)
        conn.connect()
        dpidr = conn.idr.idr
        t0 = time.monotonic()
        dhcsr = halt_now()
        halt_ms = (time.monotonic() - t0) * 1000
        break
    except exceptions.TransferError:
        if dpidr is not None:
            print(f"  DPIDR 0x{dpidr:08x} answered but halt failed; still polling", flush=True)
            dpidr = None
    if time.monotonic() >= next_report:
        print(f"  {attempts} attempts, no response yet", flush=True)
        next_report += 2

if dhcsr is None:
    probe.disconnect()
    probe.close()
    sys.exit(f"No SWD response in {attempts} attempts")

print(f"RESPONSE after {attempts} attempts: DPIDR = 0x{dpidr:08x}")
print(f"Halt issued {halt_ms:.1f} ms after DPIDR; DHCSR = 0x{dhcsr:08x} "
      f"(S_HALT={'yes' if dhcsr & (1 << 17) else 'NO'})")

print(f"CPUID      = 0x{mem_read(CPUID):08x}")
probe.write_dp(DP_SELECT, 0xF0)
ap_base = probe.read_ap(0x08)
ap_idr = probe.read_ap(0x0C)
probe.write_dp(DP_SELECT, 0)
print(f"AP0 IDR    = 0x{ap_idr:08x}   BASE = 0x{ap_base:08x}")

rom = ap_base & 0xFFFFF000
pidr = [mem_read(rom + 0xFE0 + 4 * i) & 0xFF for i in range(4)]
pidr4 = mem_read(rom + 0xFD0) & 0xFF
jep_cont = pidr4 & 0xF
jep_id = ((pidr[2] & 0x7) << 4) | (pidr[1] >> 4)
part = pidr[0] | ((pidr[1] & 0xF) << 8)
print(f"ROM table @ 0x{rom:08x}: JEP106 cont={jep_cont} id=0x{jep_id:02x} part=0x{part:03x}")

dbg = guarded_read(0xE0042000)
print(f"STM32 DBGMCU_IDCODE            = {'fault' if dbg is None else f'0x{dbg:08x}'}")

# STM32F303xE (0x446): flash size (KB) at 0x1FFFF7CC[15:0], option bytes at 0x1FFFF800,
# FLASH_OBR at 0x4002201C with LEVEL1_PROT=bit1, LEVEL2_PROT=bit2.
fsize = guarded_read(0x1FFFF7CC)
if fsize is not None:
    print(f"Flash size register           = {fsize & 0xFFFF} KB")

obr = guarded_read(0x4002201C)
ob_rdp = guarded_read(0x1FFFF800)
if obr is not None:
    lvl2 = bool(obr & 0x4)
    lvl1 = bool(obr & 0x2)
    level = 2 if lvl2 else (1 if lvl1 else 0)
    print(f"FLASH_OBR                     = 0x{obr:08x}  -> RDP level {level}")
if ob_rdp is not None:
    rdp_byte = ob_rdp & 0xFF
    name = {0xAA: "level 0 (unprotected)", 0xCC: "level 2 (locked forever)"}.get(rdp_byte, "level 1")
    print(f"Option byte RDP               = 0x{rdp_byte:02x}  -> {name}")

print("First flash words @ 0x08000000:")
for off in (0x0, 0x4, 0x8, 0xC):
    v = guarded_read(0x08000000 + off)
    print(f"  +0x{off:02x} = {'fault' if v is None else f'0x{v:08x}'}")

probe.disconnect()
probe.close()
print("Core left halted with reset vector catch set.")
