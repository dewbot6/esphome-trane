# esphome-trane

An [ESPHome](https://esphome.io) component for monitoring and controlling Trane HVAC systems that use the **ComfortLink II** communicating bus, enabling local control through Home Assistant without any cloud dependency.

Inspired by and structurally modeled after [esphome-econet](https://github.com/esphome-econet/esphome-econet).

---

## Overview

Trane ComfortLink II systems communicate over a proprietary 50kbps CAN bus connecting the thermostat, SC360 zone controller, air handler, and outdoor unit. This project taps that bus to parse the JSON-encoded messages and raw float frames, exposing full system state to Home Assistant.

Confirmed working on:
- **Thermostat**: Trane UX360
- **Controller**: Trane SC360
- **System**: Trane ComfortLink II variable-speed heat pump with gas auxiliary heat

Other ComfortLink II systems using the SC360 controller are likely compatible — field reports welcome.

---

## Features

- **Climate entity** with heat, cool, heat_cool, fan_only, and off modes
- **Presets**: Home, Away, Sleep, Boost
- **Real-time sensors**: refrigerant circuit temp, compressor demand %, supply/return air temp, room temp, humidity, setpoints
- **System status**: operating mode, demand stage (HP Stage 1/2, ID Stage 1/2, HP2+ID1/2), run timer, active alarms
- **Fault detection**: captures `IndoorStatus.E` fault strings (e.g. `TA_INV_HI`) as a dedicated text sensor
- **Bidirectional control**: setpoint and mode commands transmitted back on `0x641`
- **Sub-device grouping**: entities appear under four logical devices in HA
  - Trane Thermostat UX360
  - Trane SC360 Controller
  - Trane Air Handler
  - Trane Heat Pump

---

## Hardware

### Required

| Component | Notes |
|---|---|
| M5Stack AtomS3 | ESP32-S3 based, tested and confirmed |
| M5Stack CAN bus module | Plugs directly onto the AtomS3 |
| 24VAC to 5VDC converter | Powers the ESP from the system's 24VAC supply |

Other ESP32-S3 boards with a CAN transceiver should work — adjust `tx_pin` and `rx_pin` in the YAML to match your wiring.

### CAN Bus Connection

Tap the four wires from the ComfortLink II bus at the SC360 or any accessible point in the daisy chain:

| Wire | Signal |
|---|---|
| 24VAC | System power (to 24VAC→5VDC converter) |
| H | CAN High |
| L | CAN Low |
| GND | Ground (common to 24VAC→5VDC converter) |

> ⚠️ The bus runs at **50kbps**. This is configured in the YAML — do not change it.

> ⚠️ Use a dedicated 24VAC→5VDC converter to power the ESP. Do not attempt to power it directly from the 24VAC supply without conversion.

### ESP32 CAN Pin Assignment (M5Stack AtomS3 + CAN module)

```yaml
tx_pin: GPIO5
rx_pin: GPIO6
bit_rate: 50kbps
```

---

## Installation

### 1. Clone or download this repo

```bash
git clone https://github.com/YOUR_USERNAME/esphome-trane.git
cd esphome-trane
```

### 2. Create your secrets file

```bash
cp secrets.yaml.example secrets.yaml
# Edit secrets.yaml with your Wi-Fi credentials
```

### 3. Flash

```bash
esphome run esphome-trane.yaml
```

Or adopt the device in the ESPHome dashboard and flash from there.

---

## Repository Structure

```
esphome-trane/
├── esphome-trane.yaml          # Main ESPHome configuration
├── secrets.yaml.example        # Template for Wi-Fi credentials
├── components/
│   └── trane_hvac/
│       ├── __init__.py         # Component registration
│       ├── climate.py          # ESPHome codegen for climate entity
│       ├── trane_climate.h     # Climate component header
│       └── trane_climate.cpp   # Climate component implementation
└── README.md
```

---

## CAN Bus Architecture

The ComfortLink II bus uses four CAN node addresses:

| CAN ID range | Node |
|---|---|
| `0x641` / `0x649` | SC360 zone controller ↔ broadcast |
| `0x5C1` / `0x5C9` | UX360 thermostat ↔ SC360 (private, ISO 15765-2 transport) |
| `0x380`–`0x38F` | Outdoor unit (heat pump) float frames |
| `0x490` | Air handler float/sensor frames |

### ISO 15765-2 Transport (0x5C1 / 0x5C9)

The thermostat and SC360 exchange segmented JSON messages using ISO 15765-2 framing:

| Byte 0 | Frame type |
|---|---|
| `A0 xx` | First frame (xx = total length) |
| `A1` | Continuation frame |
| `A2` | Last frame |
| `60` / `20` / `30` | Flow control |

The JSON payload uses the same schema as `0x641`/`0x649`. This channel carries the thermostat's profile requests and the SC360's full state dump on boot.

---

## Boot Sequence Protocol

On thermostat power-on, a `PowerOnReset1` notification is broadcast on `0x641`. The thermostat then issues a series of `GetProfile` requests via `0x5C1`, triggering the SC360 to emit a full state dump in this sequence:

1. `UnitID` — model number, serial number, firmware build string
2. `SystemOpStatus` — current mode, demand stage, outdoor temp
3. `IndoorStatus` — blower state, humidity/ventilation status
4. `ZoneSettings` — per-zone mode flags
5. `ZoneStatus` — per-zone setpoints, room temps, hold state
6. `ActiveAlarms` — current fault list (if any)
7. `SpOverride` — active setpoint overrides for all zones
8. `PresetSettings` — Home/Away/Sleep setpoints
9. `SystemSettings` — thermostat name, timezone offsets, system config flags
10. `ScheduleSettings` — weekly schedule enable/disable
11. `VersionDetails` — config schema version, alarm database version
12. `ZoneCardState` — zone names and connected wireless sensor serial numbers
13. `ZoningInfo` — zone controller configuration and room name preset list
14. `WeatherData` — ZIP code, city, weather status

After the dump completes, the SC360 switches to delta-update mode — only broadcasting fields that change. This means a freshly booted ESP32 may show stale mode/setpoint values until the first real change occurs. A `GetProfile:SYSOP` request is sent on boot to accelerate initial mode population.

A TLS X.509 certificate is also transmitted on `0x649` channel `"5"` during thermostat boot — likely for mutual authentication between the UX360 and SC360. No action required; observed only.

---

## Decoded CAN Bus Fields

### `0x649` — System Broadcast (SC360 → all)

#### SystemOpStatus
| Field | Decoded meaning |
|---|---|
| `A` | System state: `A`=active, `E`=standby/error, `G`=coast-down, `I`=idle |
| `B` | System mode: `A`=heat, `B`=cool, `C`=off |
| `C` | Demand stage: `--`, `HP Stage 1`, `HP Stage 2`, `HP1+ID1`, `HP1+ID2`, `HP2+ID1`, `HP2+ID2`, `ID Stage 1`, `ID Stage 2` |
| `D` | Elapsed run time (minutes, integer string; resets each cycle) or demand % (float string, e.g. `"1"`) |
| `E` (integer string) | Indoor humidity (%) |
| `E` (float string, e.g. `"44.00"`) | SC360 outdoor ambient temperature (°F) |

#### OdStatus (per outdoor unit, keyed by unit number e.g. `"1"`)
| Field | Decoded meaning |
|---|---|
| `A` | Outdoor unit active flag (`"A"` when running) |
| `B` | Compressor speed % (0–100) |
| `C` | Outdoor unit state: `B`=running, `D`=off |
| `D` | Outdoor unit fault code (`"0"` = no fault) |
| `CompDemandPercent` | Compressor demand (%) |

#### IndoorStatus (per air handler, keyed by unit number)
| Field | Decoded meaning |
|---|---|
| `D` | Blower state: `A`=transition/off, `B`=running |
| `E` | Blower speed % (0–100) or fault string (e.g. `TA_INV_HI`) |
| `F` | Unknown (observed `"0"`) |
| `HumControl` | Humidity control state (`"0"` = off) |
| `HumidifierStatus` | Humidifier active: `"0"`=off, `"1"`=on |
| `DehumidifierStatus` | Dehumidifier active: `"0"`=off, `"1"`=on |
| `VentilatorStatus` | Ventilator active: `"0"`=off, `"1"`=on |

#### ZoneStatus (per zone, keyed by zone number `"1"`–`"6"`)
| Field | Decoded meaning |
|---|---|
| `H` | Room temperature (°F) — from thermostat or wireless sensor for that zone |
| `Hsp` / `Csp` | Active heat / cool setpoints (°F) |
| `HcStatus` | Heat/cool call: `3`=active, `4`=satisfied, `5`=elevated call, `7`=aux/emergency, `9`=inactive |
| `HoldText` | Schedule period or hold description. Schedule names: `WAKE`, `DAY`, `EVENING`, `SLEEP`. Timed hold: `"Holding Until Today HH:MM"`. Following schedule: `"Following Schedule: DAY"` |
| `E` | Airflow % (0–100) |
| `F` | Zone state: `A`=idle, `B`=active/calling |
| `G` | Zone demand % (`-99` = not participating) |

#### SpOverride (per zone, keyed by zone number)
| Field | Decoded meaning |
|---|---|
| `Hsp` / `Csp` | Override heat / cool setpoints (°F) |
| `Source` | Setpoint source: `"1"`=thermostat, `"2"`=timed hold |
| `HoldType` | `"0"`=following schedule, `"1"`=permanent hold, `"2"`=timed hold |

#### PresetSettings
| Field | Decoded meaning |
|---|---|
| `Home.Hsp` / `Home.Csp` | Home preset setpoints (°F) |
| `Away.Hsp` / `Away.Csp` | Away preset setpoints (°F) |
| `Sleep.Hsp` / `Sleep.Csp` | Sleep preset setpoints (°F) |

#### ZoneSettings (per zone)
| Field | Decoded meaning |
|---|---|
| `ZoneMode` | `"0"`=idle, `"1"`=active/calling for conditioning |

#### ZoneCardState (per zone)
| Field | Decoded meaning |
|---|---|
| `A` | Zone active flag |
| `B` | Zone display name (user-assigned, e.g. `"Zone 1"`, `"Upstairs"`) |
| `C` | Serial number(s) of wireless sensors registered to this zone |

> **Wireless sensors**: UX360 wireless temperature sensors register to a zone. Their readings appear in `ZoneStatus.H` for that zone number.

#### VersionDetails
| Field | Decoded meaning |
|---|---|
| `ConfigPropertiesVersion` | SC360 configuration schema version |
| `AlarmDetailsVersion` | SC360 alarm database version |

#### SystemSettings
| Field | Decoded meaning |
|---|---|
| `G` | Thermostat display name (user-assigned) |
| `J` | Local timezone UTC offset (e.g. `"-0700"` for PDT) |
| `K` | Standard timezone UTC offset (e.g. `"-0800"` for PST) |
| Others | Undecoded system configuration flags |

#### ScheduleSettings
| Field | Decoded meaning |
|---|---|
| `WeeklySchEnable` | Weekly schedule active: `"0"`=off, `"1"`=on |

#### ActiveAlarms (keyed by timestamp.index)
| Field | Decoded meaning |
|---|---|
| `AlarmId` | Fault code string (e.g. `Err 185.09`, `TSO 005.00`) |
| `Origin` | Originating unit (empty = SC360) |
| `Level` | Severity level |
| `Reported` | Unix timestamp of fault |

> **TSO 005.xx alarms** are transient outdoor unit sensor alarms observed briefly on power-on and auto-clearing within seconds. Not shown on the thermostat UI.

#### WeatherToday
| Field | Decoded meaning |
|---|---|
| `C` | City name |
| `D` | Current outdoor temp (°F) |
| `E` | Current outdoor humidity (%) |
| `F` | Feels-like temp (°F) |
| `G` | Forecast high temp (°F) |
| `H` | Weather condition code (observed: `P`, `Q`, `AL`, `AX`, `AZ`, `BB`) |
| `I` | Forecast low temp (°F) |

#### WeatherData
| Field | Decoded meaning |
|---|---|
| `A` | ZIP code |
| `B` | Country code |
| `C` | State/region code |
| `D` | Data status string (e.g. `"Updated less than 15 mins ago"`, `"Weather updates unavailable"`) |

#### UnitID
| Field | Decoded meaning |
|---|---|
| `ModelNumber` | Thermostat model string (e.g. `THUI2360A200UEA`) |
| `SerialNumber` | Thermostat serial number |
| `BuildInfo` | Firmware version string (e.g. `09.01.01.250508`) |

#### OutdoorSettings
| Field | Decoded meaning |
|---|---|
| `OdTempUserOffset` | Outdoor temp user calibration offset |
| `A` | Unknown outdoor settings flag |

---

### `0x641` — Command / Response

| Object | Meaning |
|---|---|
| `SpOverride Put` | Set zone setpoints (Hsp, Csp, HoldType, Source) |
| `SystemMode Put` | Set system mode (A=heat, B=cool, C=off) |
| `ZoneSettings Put` | Set zone mode |
| `GetProfile` | Request profile dump from SC360 (thermostat→SC360) |
| `RemoveProfile` | Remove cached profile |
| `PowerOnReset1` | Thermostat power-on notification |
| `Ack: 200` | SC360 acknowledgment of a command |

---

### Float Frames (Outdoor Unit — `0x380`–`0x38F`)

All values are IEEE 754 single-precision floats, little-endian.

| CAN ID | Byte layout | Sensors |
|---|---|---|
| `0x380` | Float 1 (bytes 0–3): unknown (consistently 0.0 or -99.0, possibly unused); Float 2 (bytes 4–7): outdoor air temp (°F) | Outdoor Air Temp |
| `0x386` | Float 1 (bytes 0–3): 50.0°F constant (unidentified — possibly a disconnected or stuck sensor); Float 2 (bytes 4–7): refrigerant circuit temp (°F) | Refrig Sensor B, Refrig Circuit Temp |

> **Refrig Circuit Temp** (`0x386` float 2): reads ~18–19°F during active cooling. This is likely **suction-side refrigerant temperature** or liquid line temperature after the expansion valve — NOT the outdoor condenser coil surface temperature (which would be ~110–120°F in cooling mode).

> **Refrig Sensor B** (`0x386` float 1): consistently reads 50.0°F at startup and in idle. Meaning unknown. Possibly a sensor that is not installed or not used on this system.

---

### Air Handler Frame — `0x490`

The `0x490` frame carries multiple air handler sensor readings. The 5-byte payload appears to multiplex different sensor channels; the exact byte-4 channel encoding is not yet fully decoded. Sensors currently extracted:

| Sensor | Observed range | Notes |
|---|---|---|
| Return Air Temp | 69–71°F | Return air entering air handler |
| Supply Air Temp | 69–71°F | Supply air leaving air handler (converges with return when system idle) |
| AHU Inlet Air Temp | 28–40°F during cooling | Likely refrigerant temperature at indoor coil inlet (not air temperature) |
| Indoor Coil Temp | 35–40°F during cooling, 90–98°F during heating | Indoor evaporator/condenser coil surface temperature |
| Temp Sensor 1/2/3 | 78–92°F, rising during operation | Unidentified — confirmed NOT duct sensors (system uses static pressure sensing only) and NOT wireless room sensors. Likely outdoor unit or air handler component temperatures (motor, compressor housing, or electronics). |
| Indoor Humidity | 40–55% | |

> **Wireless room sensors** (UX360 wireless accessories) do **not** appear in `0x490`. Their readings are reported via `ZoneStatus.H` for the zone they are assigned to.

---

## Operating State Mapping

Cross-referenced against the Trane Home app activity log:

| App state | CAN bus equivalent |
|---|---|
| Heat | `HcStatus=3`, `SystemOpStatus.C` = `HP Stage 1/2` or `ID Stage 1/2` |
| Cool | `HcStatus=3`, `SystemOpStatus.B` = `B`, `SystemOpStatus.C` = cool stage |
| Defrost | `SystemOpStatus.C` = `HP2+ID1` or `HP2+ID2` |
| Emergency heating | `HcStatus=7`, `SystemOpStatus.C` = `ID Stage 1/2` |
| Fan (post-cycle) | `SystemOpStatus.C` = `--`, `IndoorStatus.D` = `B` (blower running) |
| Idle | `HcStatus=4`, `SystemOpStatus.C` = `--` |
| Standby/lockout | `SystemOpStatus.A` = `E` |

App "Run mode" maps to `SpOverride.HoldType`: timed hold = `"2"`, permanent hold = `"1"`, following schedule = `"0"`.

App "Compressor speed" maps to `OdStatus.B` (0–100%).

App "Outdoor temperature" maps to `SystemOpStatus.E` (float string, SC360 ambient sensor).

---

## Climate Presets

Setpoints derived from observed schedule data on a UX360 / SC360 system:

| Preset | Hsp | Csp | Notes |
|---|---|---|---|
| Home | 68°F | 72°F | Matches observed UX360 PresetSettings |
| Away | 65°F | 82°F | Matches observed UX360 PresetSettings |
| Sleep | 66°F | 70°F | Matches observed UX360 PresetSettings |
| Boost | 74°F | 72°F | No Trane equivalent; raises heat target to encourage aux/gas staging |

These are read live from `PresetSettings` on the bus and updated in the climate entity automatically. The Boost preset uses hardcoded values since there is no Trane equivalent.

---

## Known Limitations

- **Mode on reboot**: The SC360 only broadcasts `SystemOpStatus.B` on mode changes, not on demand. Mode is restored from NVS on boot and self-corrects on the next real mode change.
- **Fan-only mode**: Maps to system off (`C`) on the CAN bus — no dedicated fan-only command has been observed.
- **Heat_cool (auto) mode**: Sends heat (`A`) — the SC360 manages HP/gas staging automatically.
- **Zone 2–6 setpoint writes**: Not yet implemented.
- **`0x380` float 1**: Consistently 0.0 or -99.0; meaning unknown.
- **`0x386` float 1 (Refrig Sensor B)**: Consistently 50.0°F at idle; meaning unknown.
- **Temp Sensor 1/2/3**: Source and exact meaning unidentified.
- **`0x490` byte 4 channel encoding**: Not fully decoded; additional sensor channels may exist.

---

## Contributing

Observations, field mappings, and tested hardware additions are very welcome. If you capture CAN traffic from a different Trane ComfortLink II system, please open an issue with your data — field decoding is ongoing.

---

## License

MIT
