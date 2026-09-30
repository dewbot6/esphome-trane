"""Catch the post-boot SWD window, halt the core, and dump flash in the same session.

The panel firmware disables SWD ~ms after boot, and disconnecting resumes the core
(which re-disables SWD), so the halt and the whole dump must happen on one connection.
Flash is read via the AHB-AP with CSW auto-increment, in 1 KB blocks (the TAR
auto-increment boundary). Read-only: only debug registers are written, never flash.

Usage: catch_and_dump.py [duration_s] [flash_base] [flash_size]
"""
import sys
import time

from pyocd.core import exceptions
from pyocd.core.helpers import ConnectHelper
from pyocd.coresight.dap import DPConnector
from pyocd.probe.debug_probe import DebugProbe

DURATION_S = float(sys.argv[1]) if len(sys.argv) > 1 else 120
FLASH_BASE = int(sys.argv[2], 0) if len(sys.argv) > 2 else 0x08000000
FLASH_SIZE = int(sys.argv[3], 0) if len(sys.argv) > 3 else 0x80000
BLOCK = 0x400  # TAR auto-increment is only guaranteed within a 1 KB window

DP_ABORT, DP_CTRL_STAT, DP_SELECT = 0x0, 0x4, 0x8
AP_CSW, AP_TAR, AP_DRW = 0x00, 0x04, 0x0C
DHCSR, DEMCR = 0xE000EDF0, 0xE000EDFC

session = ConnectHelper.session_with_chosen_probe(
    blocking=False, return_first=True, auto_open=False,
    options={"auto_unlock": False, "target_override": "cortex_m"})
if session is None:
    sys.exit("No debug probe found")

probe = session.probe
probe.open()
probe.set_clock(1_000_000)  # 2 MHz gave "Unexpected ACK 0" on sustained transfers
probe.connect(DebugProbe.Protocol.SWD)
# Block transfers (DAP_TransferBlock / auto-increment) fail on this link, but single
# word reads with a fresh TAR each time are reliable (proven by the identify step).
CSW_VALUE = 0x23000002  # HPROT | Size=word | AddrInc=OFF


def halt_now():
    probe.write_dp(DP_ABORT, 0x1E)
    probe.write_dp(DP_CTRL_STAT, 0x50000000)
    for _ in range(20):
        if probe.read_dp(DP_CTRL_STAT) & 0xA0000000 == 0xA0000000:
            break
    else:
        raise exceptions.TransferError("debug power-up not acknowledged")
    probe.write_dp(DP_SELECT, 0)
    probe.write_ap(AP_CSW, CSW_VALUE)
    probe.write_ap(AP_TAR, DHCSR)
    probe.write_ap(AP_DRW, 0xA05F0003)          # C_DEBUGEN | C_HALT
    probe.write_ap(AP_TAR, DEMCR)
    probe.write_ap(AP_DRW, 0x1)                 # VC_CORERESET (write-only; avoids stale read)
    # AP reads are pipelined: first read returns stale data, so read DHCSR twice.
    probe.write_ap(AP_TAR, DHCSR)
    probe.read_ap(AP_DRW)
    probe.write_ap(AP_TAR, DHCSR)
    return probe.read_ap(AP_DRW)


print(f"Polling for {DURATION_S:.0f}s - power-cycle the panel now", flush=True)
deadline = time.monotonic() + DURATION_S
next_report = time.monotonic() + 2
attempts = 0
dhcsr = None
while time.monotonic() < deadline:
    attempts += 1
    try:
        DPConnector(probe).connect()
        dhcsr = halt_now()
        break
    except exceptions.TransferError:
        pass
    if time.monotonic() >= next_report:
        print(f"  {attempts} attempts, no response yet", flush=True)
        next_report += 2

if dhcsr is None:
    probe.disconnect(); probe.close()
    sys.exit(f"No SWD response in {attempts} attempts")

if not dhcsr & (1 << 17):
    probe.disconnect(); probe.close()
    sys.exit(f"Caught SWD but core not halted (DHCSR=0x{dhcsr:08x}); aborting")

print(f"Halted after {attempts} attempts (DHCSR=0x{dhcsr:08x}). Dumping "
      f"0x{FLASH_SIZE:x} bytes @ 0x{FLASH_BASE:08x}...", flush=True)


def read_word(addr):
    for attempt in range(5):
        try:
            probe.write_ap(AP_TAR, addr)
            return probe.read_ap(AP_DRW)
        except exceptions.TransferError:
            probe.write_dp(DP_ABORT, 0x1E)
            if attempt == 4:
                raise
            time.sleep(0.005)


def read_flash(label):
    probe.write_dp(DP_SELECT, 0)
    probe.write_ap(AP_CSW, CSW_VALUE)
    buf = bytearray()
    step = FLASH_SIZE // 16
    t0 = time.monotonic()
    for off in range(0, FLASH_SIZE, 4):
        buf += read_word(FLASH_BASE + off).to_bytes(4, "little")
        if off and off % step == 0:
            pct = 100 * off // FLASH_SIZE
            print(f"  [{label}] {pct}% ({off//1024} KB, {time.monotonic()-t0:.0f}s)", flush=True)
    return bytes(buf)


t0 = time.monotonic()
first = read_flash("read1")
# Sanity-check the vector table before spending time on a second full pass.
sp, rv = int.from_bytes(first[0:4], "little"), int.from_bytes(first[4:8], "little")
print(f"Vector table: SP=0x{sp:08x} reset=0x{rv:08x}", flush=True)
if not (0x20000000 <= sp <= 0x20010000 and 0x08000000 <= rv < 0x08080000):
    with open(f"dumps/flash-SUSPECT-{time.strftime('%Y%m%d-%H%M%S')}.bin", "wb") as f:
        f.write(first)
    probe.disconnect(); probe.close()
    sys.exit("Vector table looks wrong; saved as SUSPECT, not trusting this read")
second = read_flash("read2")
dt = time.monotonic() - t0
probe.disconnect()
probe.close()

stamp = time.strftime("%Y%m%d-%H%M%S")
path = f"dumps/flash-0x{FLASH_BASE:08x}-{stamp}.bin"
match = first == second
if match:
    with open(path, "wb") as f:
        f.write(first)
    ff = first.count(0xFF) / len(first)
    print(f"MATCH: two reads identical, {len(first)} bytes in {dt:.1f}s")
    print(f"Wrote {path}  ({ff:.1%} 0xFF)")
    import hashlib
    print("sha256:", hashlib.sha256(first).hexdigest())
else:
    with open(path + ".read1", "wb") as f:
        f.write(first)
    with open(path + ".read2", "wb") as f:
        f.write(second)
    diffs = sum(a != b for a, b in zip(first, second))
    print(f"MISMATCH: {diffs} differing bytes; wrote .read1/.read2 for inspection")
