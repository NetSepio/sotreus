# Sotreus Android V3 — Mesh mode

Status: planned on branch `mesh-mode` · Date: 2026-11-12 · Owner: NetSepio

V3 = **when the paired Edge runs in Mesh mode, Sotreus is its Meshtastic-compatible phone app**:
messages, nodes, map, channels and mesh settings, over the existing Bluetooth link and protocol v1.
It sits on top of `HANDOFF_V1_UI.md` (design system, copy rules) and `HANDOFF_V2_EDGE.md` (pairing,
link, sync). Build plan: `docs/plans/mesh-mode.md`. Protocol: `../sotreus-edge/docs/protocol.md`
§2 (`mesh_*` commands), §3 (mesh events), §4 type 4, §6 `mesh` config, §7 Meshtastic compatibility.

## 0. Authority

| Topic | Wins |
|---|---|
| Mesh scope, mesh screens, the amendments in §3 | this file |
| Edge link, pairing, sync, location of Edge sightings | `HANDOFF_V2_EDGE.md` |
| Everything else in UI | `HANDOFF_V1_UI.md` |
| Architecture, data model, all other privacy rules | architecture handoff |

§3 amends three rules, for the mesh only (owner decision 2026-11-12): location stays on the device
(architecture handoff §2, rule 3), "do not request background location" (architecture handoff §13,
Location) and "no background location permission" (`HANDOFF_V2_EDGE.md` §4.7). Anywhere else,
the stricter rule still wins.

## 1. Scope

### In V3 (Android, this branch)

- **Navigation.** In Mesh mode the first tab is Mesh instead of Now (§2).
- **Messages.**
  - Channels (up to 8) and direct messages.
  - Send, replies, emoji reactions and quick messages.
  - Delivery state: sent, relayed, delivered, no reply, failed.
  - Encrypted direct messages are marked.
  - Missed messages can be fetched from a store & forward router.
  - New messages raise a notification.
- **Nodes.**
  - The list shows: names, role, last heard, hops, last packet's SNR/RSSI, battery and telemetry,
    favourite or ignored, key known. The Edge holds up to 64 nodes; this phone keeps every node it
    has seen.
  - Node detail shows device and environment telemetry, neighbours and the reported position.
  - Actions: message, traceroute (route and SNR both ways), request position, favourite, ignore.
- **Map.** Shows this phone's position, nodes' reported positions and waypoints, with the distance
  and bearing to each node (§3.2). Waypoints can be added, edited and expired.
- **Position on the mesh.** This phone's location by default, a point picked on a map, or off
  (§3.1).
- **Channels.**
  - List, add and edit: name, key, position precision, MQTT uplink and downlink.
  - Share as a Meshtastic link and a QR code.
  - Import a link or QR code, either replacing all channels or adding to them.
- **Mesh settings.**
  - Owner: long and short name.
  - Radio: region (while it is UNSET the radio never transmits), preset, role, hop limit,
    rebroadcast and TX power. An unset region is filled from this phone's mobile network country
    when that names one region, and the Mesh tab says so; GPS is not used for it, since naming a
    country from a fix needs an online lookup (owner, 2026-11-13).
  - Node-info and telemetry intervals, licensed (ham) mode, and a new identity.
  - Mesh status: channel use, own airtime, polite hold, counters.
- **Modules.**
  - MQTT: broker, write-only credentials, encryption, JSON, TLS, map reports.
  - Store & forward server, admin (open or listed keys) and remote hardware pins.
  - Neighbour info, range test interval, paxcounter, detection sensor, serial bridge and external
    notification.
- **TAK.** ATAK users heard, GeoChat, TAK position report, team and role.
- **Journey and Sit** while the Edge is in Mesh mode (§2.2).
- **Mode switch** on the Edge screen (done on this branch).

### Not in V3

- The Meshtastic phone API (`ToRadio`/`FromRadio`), so neither the Meshtastic app nor an ATAK
  plugin can use Sotreus as a bridge. The Edge does not implement it (protocol §7).
- Remote admin of other nodes from the app. Protocol v1 has no command for it.
- Voice, OTA over the mesh, custom modem parameters (protocol §7).
- iOS.

## 2. Navigation

### 2.1 Mesh replaces Now

- The first tab is **Mesh** while the paired Edge's last known mode is Mesh and the Edge is the
  sensing source. "Last known" keeps messages readable while the Edge is out of reach; Mesh then
  says Out of reach and shows what is stored.
- It goes back to **Now** when the Edge switches to Awareness, when it is forgotten, or when the owner
  picks this phone's radios as the source. In that last case Now shows "Your Edge is in Mesh mode"
  with Open Mesh.
- Inside Mesh: underline tabs Messages · Nodes · Map, and Mesh settings. Mesh settings is also
  reachable from Settings › Sotreus Edge.
- History, Journey, Context and Settings are unchanged.

### 2.2 Journey and Sit in Mesh mode

- In Mesh mode the Edge hears no devices. Starting a Journey or Sit asks first: "Your Edge is in
  Mesh mode and hears no devices. Use this phone's radios for this journey? When it ends, Sotreus
  goes back to your Edge." The buttons are Use this phone's radios and Cancel.
- The phone's radios run for that session only. The source setting does not change, so the bar does
  not flip mid-session. The live screen shows a "This phone's radios" chip.
- No session starts this phone's scanning without that answer.

## 3. Amendments (owner decision 2026-11-12)

### 3.1 This phone's position on the mesh

- **Default: share this phone's location.** The Edge's `mesh.position.mode` is set to `client`, and
  the app sends fixes with `mesh_set_position`. Sotreus sets this the first time it sees this Edge
  in Mesh mode, unless the owner has already chosen in the app.
- **A stationary Edge with a set position** defaults to that position instead: `fixed` at its
  placement. This phone's location is not needed, and is not where the Edge is (owner, 2026-11-13).
- **Disclosure first (M8).** Before any location prompt, one screen says:
  - what is sent, and to whom: anyone on each channel, at that channel's precision;
  - how often it is sent, and that it continues while Sotreus is closed;
  - where to change it.
  The choices are Share my location (preselected), Pick a point on a map, and Don't share. A
  stationary Edge with a set position also gets "Your Edge's set position", preselected.
- **Background location.** `ACCESS_BACKGROUND_LOCATION` is requested for this feature only, after
  fine location, as Android requires: Allow all the time, in system settings. Without it, sharing
  runs only while Sotreus is on screen, and Mesh settings says so.
- **Visible.**
  - Sharing runs under the existing Edge link service, which adds the `location` type while it
    shares.
  - The notification says "Sharing your location on the mesh".
  - The Mesh tab shows a chip while sharing is on.
- **Precision.** Each channel's `position_precision` decides what leaves the radio. The default
  channel uses 13 bits, about ±2.9 km. Mesh settings shows the precision per channel.
- **Switchable.** In Mesh settings: This phone's location, A point on a map (`fixed`, with the same
  map picker as a stationary Edge), or Off. The choice is written to the Edge at once.
- **Only in Mesh mode.** Positions go out only while the Edge is in Mesh mode and connected. Nothing
  is sent in Awareness mode. The app keeps no log of the positions it sends.
- **Travel tagging is unchanged.** Tagging (V2 §4) never uses the background permission.
- **Store listing.** Google Play needs a background-location declaration matching this disclosure.

### 3.2 Node positions, distance and bearing

- A node's position is what the node reports, so it is NETWORK data. The Mesh map draws it as a pin in
  the Network colour, with its age and precision: "Reported by the node · 4 min ago · ±2.9 km".
- Distance and bearing come only from this phone's position and the node's reported position. They
  are rounded to the precision ("about 3 km"), and hidden when either position is unknown or stale.
  They never come from RSSI or SNR; signal stays proximity only.
- A reported position older than 2 hours is labelled stale.
- This exception covers mesh nodes only. Devices that the Edge or this phone senses keep the
  no-direction, no-distance, no-pin rule. That includes Meshtastic radios matched by the signature
  catalog.

### 3.3 What stays on this phone

- Messages, nodes and waypoints are stored on this phone only, covered by retention and erase, and
  left out of exports unless the owner includes them. Network clients never receive them.
- MQTT is the Edge's own internet link, off by default. Mesh settings says what it uplinks, including
  map reports, which carry a position.

## 4. Screens

Mocks in `design/screens/`, 390 dp wide, same tokens, drafted and reviewed before any Compose code.

| # | Screen | Content |
|---|---|---|
| M1 | Mesh · Messages | channels and direct conversations, unread, last message, delivery state, link and sharing chips |
| M2 | Conversation | bubbles with sender, time, hops and SNR, delivery state, reply, reaction, quick messages, compose (200 bytes) |
| M3 | Mesh · Nodes | list with favourites first, heard-recently filter, sort |
| M4 | Node detail | telemetry, neighbours, reported position with distance, actions, traceroute result |
| M5 | Mesh · Map | this phone, nodes, waypoints, distance and bearing, add waypoint |
| M6 | Channels | list, edit, share link and QR, import |
| M7 | Mesh settings | position, owner, radio, modules, TAK, status |
| M8 | Position disclosure | the first time this Edge is seen in Mesh mode |
| — | Journey/Sit ask | existing `ConfirmDialog`, no mock |

## 5. Copy

- Same rules: calm, evidence-first, banned words, banned-copy test.
- Delivery states read Sent · Relayed · Delivered · No reply · Failed, with the reason when there is
  one.
- An encrypted direct message reads "Encrypted to this node". Never "secure" or "verified".
- Meshtastic role and preset names are shown the way Meshtastic shows them.
