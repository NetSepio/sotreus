# Sotreus Android V2 — Sotreus Edge as the sensor

Status: in progress on branch `edge-client` · Date: 2026-11-06 · Owner: NetSepio

V2 = **the Edge is the sensor; the phone is the screen, the memory and (when needed) the
location.** It sits on top of `HANDOFF_V1_UI.md` (design system, screens 01–16, copy rules) and the
architecture handoff (`docs/SOTREUS_ANDROID_CODEX_HANDOFF.md`, data model, privacy). Build plan and
decisions: `docs/plans/edge-client.md`. Protocol: `../sotreus-edge/docs/protocol.md` (v1).

## 0. Authority

| Topic | Wins |
|---|---|
| V2 scope, links, deployment modes, Edge location rules (§4) | this file |
| Everything else in UI | `HANDOFF_V1_UI.md` |
| Architecture, data model, all other privacy rules | architecture handoff |

§4 amends the architecture handoff's rule that location is stored only inside an explicit
geotagged session (§13 "Location"). The amendment covers Edge sightings only. If anything else
conflicts on privacy, the stricter rule wins.

## 1. Scope

### In V2 (Android, this branch)

- Pair a Sotreus Edge over Bluetooth (passkey on its display); capability discovery from `info.caps`.
- The Edge replaces this phone's BLE and Wi-Fi scanning while it is the chosen source.
- Live view (Now, attention, sessions, proximity check through `watch`) and resumable,
  idempotent sync of the Edge's record log into History.
- Location for every sighting, from the best available source, with its quality (§4).
- Deployment modes: travel and stationary, stored on the Edge.
- Edge device screen with the interactive Sotreus Edge 3D model (from the pitch deck).
- Sweep view and category-guess List tab on Now.
- Wi-Fi data links: a burst over the Edge's own network for big syncs; a shared network for a
  stationary Edge.
- Firmware additions in `sotreus-edge`: `seen.body`, `live`, `placement` (all additive, protocol v1).

### Not in V2 on this branch

- Mesh mode UI (messages, nodes, channels, waypoints, TAK) → separate later branch. The app shows
  the mode and can switch it, nothing more.
- iOS → phase 2 (`:edge:protocol` is Android-free so it can be shared).
- OTA firmware update from the app, signed firmware manifest.
- GNSS firmware (no supported board has GNSS yet). The app side is ready for it (§4.2).
- Anything drawn on or sent to the Edge's own display.

## 2. Data quality principle

Sotreus is only as aware as its evidence is good. V2 prefers **no value over a wrong value**:

- Every sighting has a time from the Edge's clock (corrected for measured offset), or is kept
  apart as "time not known".
- Every sighting has a location with a source and an accuracy, or says "where not known".
- Nothing is filled in from a last known value, interpolated across a gap, or shown fresher than
  it is.
- Quality limits (§4.4) are defaults the user can tighten or loosen in Field tools, never remove.

## 3. Links

- **Pairing: Bluetooth only.** The 6-digit code on the Edge's display proves possession; LE
  Secure Connections gives an encrypted, MITM-protected bond. Wi-Fi details (`access`) are fetched
  over the bond, so Wi-Fi needs no typing.
- **Bluetooth is the default data link** for live data and normal catch-up sync. It does not cost
  the Edge any Wi-Fi listening (it keeps hopping all 13 channels).
- **Wi-Fi, two cases:**
  - *Burst over the Edge's own network* when a large backlog (> ~5,000 records) waits: join, pull
    `/api/records?fmt=bin`, leave. Android shows its system prompt each time.
  - *Shared network* for a stationary Edge: found by mDNS (`_sotreus._tcp`), PIN → token
    (`/api/login`), records over HTTP from anywhere on that network. Tokens live in the Edge's RAM,
    so the app logs in again after a reboot or PIN change. The PIN is stored Keystore-wrapped.
- While the Edge is joined to a Wi-Fi network (its own with a client, or a shared one) it listens
  to Wi-Fi on **one channel**. The Edge screen says so whenever a Wi-Fi link is chosen.
- Wi-Fi transport is plain HTTP with a bearer token (no TLS on the Edge). Later: encrypt payloads
  with a key agreed over the Bluetooth bond.
- **Delete after sync.** The Edge's log is a buffer. After each committed page the app sends
  `sync_confirm` with the newest seq in its database, over Bluetooth or HTTP; the Edge erases
  those records. The cursor and the confirmation never pass a record that is not in the database
  (a corrupt record blocks both until it has been seen corrupt twice). Records nothing reads yet
  (mesh text, Edge events, positions) are kept raw so they can be confirmed too. Seqs have gaps;
  `status.log.count` is the stored count, and a jump counts as lost only when the ring is full.
- **Hand-off while connected.** On every Bluetooth connect: sync, confirm, then `stream_start`;
  new records come straight to the phone, go through the same store path (deduplicated by
  `device_id` + seq) and are acknowledged with `stream_ack` (cumulative, every 16 records or 2 s).
  Only an unbroken run of committed records is acknowledged: a missing, unreadable or unsaved
  record ends the hand-off with `stream_stop`, the Edge stores the rest, and a sync brings it in.
  When the hand-off ends for any reason the app syncs and starts it again. The Edge screen says
  "New records go straight to this phone" while it runs.

## 4. Location for Edge sightings (amends architecture handoff §13)

### 4.1 Meaning

A sighting's location is the **Edge's location at time of observation**. The radio was somewhere
within range of it. Maps draw the Edge's point with its accuracy circle; never a pin on the other
radio. Use the wording "Edge location at time of observation".

### 4.2 Sources

| Order | Source | When |
|---|---|---|
| 1 | `EDGE_GNSS` | `caps.gnss` and the Edge has a fix (future boards; record type 6) |
| 2 | `EDGE_FIXED` | Stationary deployment with a position set |
| 3 | `PHONE_WITH_EDGE` | Travel deployment, or a GNSS Edge without a fix, while phone and Edge are together |
| 4 | `NONE` | Anything else |

With sources 1 and 2 the phone's location is not used for Edge data.

### 4.3 Deployment modes (stored on the Edge in `placement`)

- **Travel**: carried with you. The phone's location tags sightings only while together. When
  they separate, tagging and the phone's location updates stop at that moment; Companion Device
  Manager reports when the Edge is back and tagging resumes.
- **Stationary**: left in one place. Position set once, three ways:
  - "Set from this phone": averaged up to 30 s, accepted at ≤ the limit in Field tools (default
    30 m). An estimate outside the limit stays on screen with its accuracy and the reason, and
    "Save anyway (±N m)" stores that real accuracy, never a better one. Missing permission or no
    fixes are said plainly, with the permission prompt when it is missing.
  - "Pick on map": accuracy from the zoom (the ground under the pin, at least ±10 m), source "map".
  - "Type coordinates": "lat, lon"; at least ±25 m, wider when fewer decimals are typed; source "map".
  After saving, the app reads `config.placement` back and shows "Position set · ±N m · <source>".
  Applies to every sighting from that Edge, with or without the phone present.
- **Moved checks** (stationary): the strong Wi-Fi neighbourhood the Edge hears (top 20 access
  points over the first 30 minutes) is compared with each sync's last hour; under 30 % overlap, an
  Edge reboot, or a strongly linked phone with a good fix > 200 m away → stop applying the position
  from the estimated change and ask "Is your Edge still at <place>?".

### 4.4 Phone fix quality checks (travel)

All must hold: **together** (Bluetooth link to the Edge averages ≥ −75 dBm over 15 s; a Wi-Fi link
does not count), **accurate** (horizontal ≤ 25 m), **recent** (≤ 10 s old when joined), **real**
(not mock; no active spoofing warning when GNSS checks are on), **clock-aligned** (Edge clock
offset measured at connect and every 10 minutes; `set_time` resent beyond 1 s).

### 4.5 What is captured with each fix

When the phone provides it: latitude, longitude, horizontal accuracy; altitude (WGS84) and
vertical accuracy; mean-sea-level altitude (Android 14+); speed and its accuracy; provider;
satellites used and in view; mock flag; GNSS health state; barometric pressure if the phone has a
barometer. Altitude is shown only with its vertical accuracy.

### 4.6 How sightings get their location

- Live samples (every 4 s): nearest passing fix within ±5 s; uncertainty = accuracy + speed × Δt.
- Sighting windows (records, up to 5 minutes), counting only time together: **point** (coverage
  ≥ 80 %, span ≤ 50 m), **along route** (coverage ≥ 80 %, span > 50 m), **partial** (20–80 %),
  **not known** (< 20 %).
- Encounters keep first and last location; entities keep "last heard" time, location, accuracy
  and source.

### 4.7 Privacy

Explicit (chosen at pairing; on by default for travel, with the explanation on screen), visible
(persistent notification and Now chip while tagging), switchable (Edge screen), foreground service
only (`connectedDevice` + `location`), no background location permission, on-device only, covered
by retention and erase, coarse in export by default.

The Edge link itself keeps running while Sotreus is not on screen whenever the Edge is the sensor,
in both modes, under the same visible foreground service (owner decision 2026-11-08). It adds the
`location` type only while travel tagging is on.

## 5. Screens

Mocks in `design/screens/`, same design system as V1:

| # | Mock | Screen |
|---|---|---|
| 17 | `17-edge-pair.html` | Pair → code → deployment → location tagging → first sync |
| 18 | `18-edge-device.html` | Edge device: 3D model, status, deployment and position, data link, awareness switches, sync, mode, forget, recovery |
| 19 | `19-now-edge-sweep.html` | Now, Sweep view |
| 20 | `20-now-edge-list.html` | Now, List with category guesses |

Required phrases, verbatim where they appear:

- "Rings group radios by signal strength."
- "Edge location at time of observation" (location row label).
- "While on Wi-Fi, your Edge listens to Wi-Fi on one channel only." (Wi-Fi link choices).

Keep screens lean (owner decision 2026-11-06): no "X is not Y" disclaimer sentences under
visuals. The rules behind them still hold in the design (hash-placed dots, no bearing, no pin on
the other radio); TalkBack summaries may state them.

V1's banned-copy list applies unchanged.

## 6. Edge capabilities

`EdgeCapabilities` is parsed from `info.caps`; unknown keys are ignored. Known keys:
`ble_observation`, `wifi24_observation`, `wifi_probe_observation`, `gnss`, `local_storage`,
`firmware_switching`, `meshtastic_mode`, `lora`, `display`, `battery`, `ota_upload`, and from V2
`live_sightings`, `placement`. The UI only offers what the capabilities allow.
