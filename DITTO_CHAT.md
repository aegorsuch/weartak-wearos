# Ditto GeoChat wire contract

Collection: `chat`, matching the official Ditto Edge Sync ATAK plugin
1.1.1 `da2b2218` (ATAK 5.8 CIV). The field contract was verified by local,
read-only inspection of the supplied APK; no plugin implementation or
credentials are included here. Inspected APK SHA256:
`0ab3a022a75c3c187d473d7620e9c9e2f1a4fb3ea65203d331ad6bb70233740f`.

## Native fields

| Field | Type | Meaning |
|---|---|---|
| `_id` | string | Original `__chat.messageId`, **not** the full CoT event UID |
| `_v` | number | `2` |
| `_r` | boolean | `false`; removed documents are ignored |
| `_c` | Ditto PN counter | Native CRDT counter, not a numeric register; ignored by WearTAK receive |
| `a` | string | Originating Ditto peer key string |
| `b` | number | Event time, integer Unix milliseconds |
| `d` | string | Publishing device UID, as in native mapitem/track/alert |
| `e` | string | Sender callsign |
| `msg` | string | Plain message text |
| `room` | string | Conversation display title |
| `roomId` | string | Conversation/recipient UID, from `chatgrp.id` |
| `parent` | string | Parent contact group, normally `RootContactGroup` |
| `authorType` | string | Sender platform CoT type, e.g. `a-f-G-U-C` |
| `authorLoc` | string | Outgoing `latitude,longitude,hae`. Incoming accepts ATAK GeoPoint `latitude,longitude,altitude,ALTREF,ce,le` and any form with at least two comma-separated fields (trimmed). Latitude/longitude must be finite/in range; missing/non-finite altitude is unknown (`9999999` in synthesized CoT). Only HAE altitude is used; AGL/other references are not treated as HAE. Optional nonnegative finite CE/LE are preserved, otherwise unknown (`999999`). |

WearOS writes `_c` using SDK 4's `DittoCounter()`. DQL clients must omit `_c`
from the initial document, then use
`UPDATE chat APPLY _c PN_INCREMENT BY :increment WHERE _id = :id`.
Assigning an ordinary number creates a register, not the plugin's CRDT counter.

## Optional WearTAK extensions

These preserve lossless CoT addressing across WearOS/iOS and other transports.
Native ATAK documents do not need them.

| Field | JSON type | Meaning |
|---|---|---|
| `eventUid` | string | Original full CoT event UID; absent: derive `GeoChat.<d>.<roomId>.<_id>` |
| `r` | string | Original complete `<detail>...</detail>`, including `__chat`, `chatgrp`, `remarks`, `marti` |
| `n`, `o` | number | Start/stale integer Unix ms; absent: `b` / `b + 86400000` |
| `g` | string | CoT version; absent: `2.0` |
| `h`, `i`, `j`, `k`, `l` | number | CE, HAE, latitude, LE, longitude; absent: errors `999999`, coordinates from `authorLoc` |
| `p`, `q` | string | CoT how/access; absent: `h-g-i-g-o` / `Undefined` |

If `r` is present, sender, message ID, room UID/title, and message text must agree
with native fields. It must contain exactly one chat, chat group, and remarks.
Never trust a mismatching extension or replace original addressing with a broadcast.

Example JSON (the native `_c` CRDT is created by the SDK, so is omitted here):

```json
{
  "_id": "m1",
  "_v": 2,
  "_r": false,
  "a": "phone-peer-key",
  "b": 1792800000000,
  "d": "phone",
  "e": "PHONE",
  "msg": "Hello",
  "room": "WATCH",
  "roomId": "watch",
  "parent": "RootContactGroup",
  "authorType": "a-f-G-U-C",
  "authorLoc": "0.0,0.0,0.0",
  "eventUid": "GeoChat.phone.watch.m1",
  "n": 1792800000000,
  "o": 1792886400000,
  "g": "2.0",
  "h": 999999,
  "i": 0,
  "j": 0,
  "k": 999999,
  "l": 0,
  "p": "h-g-i-g-o",
  "q": "Undefined",
  "r": "<detail><__chat parent='RootContactGroup' groupOwner='false' messageId='m1' chatroom='WATCH' id='watch' senderCallsign='PHONE'><chatgrp uid0='phone' uid1='watch' id='watch'/></__chat><link uid='phone' type='a-f-G-U-C' relation='p-p'/><remarks source='phone' to='watch' time='2026-10-24T00:00:00.000Z'>Hello</remarks><marti><dest uid='watch'/></marti></detail>"
}
```

## Addressing, replay, and expiry

- Direct: `roomId` is the recipient UID. GeoChat IDs match it, `uid0` is sender,
  `uid1` is recipient, and `remarks.to` is recipient. The receiving inbox room
  is keyed by sender UID, not recipient UID.
- Named group: `roomId` is a stable group UID, `room` is its display title.
  The `r` extension preserves `uid1..N` recipient device UIDs (not the group UID)
  and any `marti.dest.uid` list. Only listed members accept it; replies retain
  the verified group membership.
- Broadcast: `roomId`, GeoChat IDs, `uid1`, and `remarks.to` use the exact literal
  `All Chat Rooms`. No `marti` destination is needed.
- Native plugin documents lack recipient rosters. Native direct and All Chat
  Rooms are reconstructed from native fields. A native named group is accepted
  only if the local inbox already has verified membership for that group.
  Unknown group IDs are not treated as broadcasts.
- Ingest through the existing GeoChat parser/inbox. Ignore local peer `a` or
  sender `d`/`chatgrp.uid0`. Deduplicate across all transports by sender UID plus
  `_id`/`__chat.messageId`, independent of receive time.
- Preserve the same message ID and full event UID on every transport.
  A persisted peer/event UID/time/fingerprint watermark suppresses repeated
  Ditto snapshots and restarts until stale. Legacy GeoChat in `mapitem`
  remains readable; new publications use only `chat`.
- Outbound TTL is 24 hours. Native stale is `b + 86400000`; optional `o` may
  shorten, never extend it. Drop stale `<= now`, reject stale before event time
  and event times over five minutes in the future.
  At service start when due, and approximately daily thereafter, locally evict
  documents whose `o` has passed or whose `b` is at least 24 hours old, using
  one combined predicate. A persisted cutoff prevents repeated eviction on
  service restart; failed cleanup is reported and retried the next day.
  Cancel the chat subscription before eviction and re-register
  with `(o IS MISSING OR o IS NULL OR o > cutoff) AND b > cutoff - 86400000` afterwards,
  so the same expired history is not fetched again. Independently refresh that
  subscription cutoff approximately hourly, cancel-then-replace with no eviction.
  Verify no expired matches
  remain before reporting cleanup success. Ingest still checks current time
  on every snapshot: cleanup cadence never makes stale data eligible.
  No replicated delete is sent, and existing inbox history is never deleted
  by mesh expiry.   V4 eviction triggers a full mesh resync; it must never run on publication retries.
  Track, mapitem and alert have no periodic eviction. Their ingestion enforces
  stale timestamps independently of storage cleanup (expired newer revisions
  still remove only their source's prior state; alert cancellations still apply).
  Expired active alert snapshots never create alerts.

  See [Ditto v4 storage management](https://docs.ditto.live/sdk/v4/sync/device-storage-management)
  for eviction's resync cost and subscription replacement requirements.
- Reject malformed XML, DTD/entities, invalid coordinates, and payloads over
  256 KiB. Snapshot limits are 4,096 docs and 2 MiB of combined `r`/`msg`
  characters; the replay journal is bounded to 8,192 entries.
- Honor Ditto enablement/identity and the chat receive setting. A connected
  bonded companion suppresses direct Ditto send and receive, as for the other
  collections. Enabling/reconnecting companion alone does not suppress Ditto.
  With Commands enabled, allow-listed leading `#weartakcommand...` messages use
  the shared command runtime before inbox/plugin dispatch. Original event time
  must be within five minutes and sender/message ID deduplicates every route.
  Native replies retain the original direct/group/broadcast room and verified
  roster, and publish only to Ditto. `toggleditto off` attempts its acknowledgement
  before stopping Ditto; publication does not confirm remote delivery.
  `setpdl`/`usep12`/`enrollserver` never enter the local inbox, even with Commands disabled.
  Enrollment commands are not published by the watch; received native/legacy
  enrollment documents are locally evicted after processing and on subsequent
  snapshots. Their replay marker is payload-independent. SDK persistence/relay
  before observer dispatch and copies on other peers cannot be undone; see
  [chat enrollment](ENROLLSERVER.md).
  Other configuration/plugin/package handlers remain unavailable via mesh.
- Recipient filtering is UI addressing, not encryption or peer access control.

## Watch power and sync policy

### Live settings diagnostics

**Plugins > Ditto > Ditto Identity**, below PDL Help, shows read-only direct-peer
counts and current BLE recovery state. Counts come from SDK 4.14.6 presence
snapshots: inspect local and remote connection lists, retain only connections
with this watch as an endpoint, then count distinct opposite peer keys per
transport. Repeated edges, reversed directions and multiple sockets do not
inflate counts. One peer linked over both BLE and LAN counts in both transport counts;
the leading **Peers** count is their unique union, not the sum or `remotePeers.size`.
No peer identifiers are displayed or logged.

The [SDK connection types](https://software.ditto.live/android/Ditto/4.14.6/api-reference/ditto/live.ditto/-ditto-connection-type/index.html)
map Bluetooth to BLE, AccessPoint to Wi-Fi LAN, P2PWiFi to Wi-Fi Aware/Direct
(the SDK does not distinguish them), and WebSocket to a remote server link.
These are established SDK-reported links, not configured enable flags,
reachable mesh peers, expected-peer health, radio activity or proof of delivery.
Peer counts share one wrapping summary, e.g. `3 Peers (2 BLE, 3 LAN)`, with no
diagnostics heading. BLE Recovery remains a separate read-only status row.
BLE and LAN rows require their public Android hardware features; temporary
radio/discovery windows or Wi-Fi disconnection do not make supported hardware
disappear. The Wi-Fi Aware row additionally requires public `FEATURE_WIFI_AWARE`
and the existing policy's enabled Aware config; Samsung's private Wi-Fi Direct
feature is not sufficient. A WebSocket row requires an actually configured
endpoint, retained across temporary internet gating. Unsupported/unconfigured
rows are hidden, never shown as misleading zeroes. This display filtering does
not change transports or networking policy.
Unavailable counts are not reported as zero. Sync suppression, stop and service
replacement clear previous counts; resumption waits for an SDK graph read.

Recovery is emitted by the existing controller: Active, Timed out, User paused,
Ineligible (service/power/Companion/radio/permission gates), or Stopped.
Unavailable is shown before a live recovery state exists. Active does not mean
continuously scanning or connected. No countdown is displayed. Observation uses
the existing presence registration and lifecycle-aware UI collection, adds no
scanning or polling, and never invokes Retry. Opening the identity page no
longer explicitly requests a discovery window; existing activity-resume,
wrist/settings and explicit Retry policy remain unchanged.

An emulator-only offline UI fixture uses the same production settings page:
`gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PweartakDittoUiDemo=true -PweartakDebugAbi=x86_64`.
This opt-in debug package is `com.aitech.weartak_civ.ditto.diagnostics.fixture`,
separate from `.dev`, with a **Ditto diagnostics - SYNTHETIC** launcher entry.
It uses a non-operational Application, no SDK instance, DataStore writes or
authentication, and a persistent **SYNTHETIC / OFFLINE** label outside production
content. Run `DittoDiagnosticsEmulatorTest` with its configured instrumentation
runner on a round emulator for count, recovery, unavailable, large-font,
read-only and back-navigation evidence. Fixture screenshots are not live peer
or physical-radio acceptance evidence. The flag is rejected for release tasks.

### Transport and recovery policy

- Wi-Fi Aware requires Android's public `FEATURE_WIFI_AWARE`. Ditto Android
  4.14.6 exposes BLE, LAN and Aware, not a separate Wi-Fi Direct switch; no
  Wi-Fi Direct transport is enabled, regardless of Samsung's private
  `sem.hardware.wifi.direct` feature. LAN is enabled only with a connected
  Wi-Fi network, including local-only Wi-Fi. Network callbacks reconfigure it
  without polling. Existing configured WebSocket URLs, if any, are enabled only
  with validated internet; this app's offline SharedKey setup configures none.
  Transport-set changes are logged without URLs or credentials.
- BLE discovery opens bounded 30-second windows and, while no direct BLE link
  exists, retries automatically after idle gaps of nominally 30, 60, 90, then
  120 seconds repeating. Each gap has +/-10% jitter so co-located watches do not
  synchronise, clamped so no gap exceeds 120 seconds (actual 27-33, 54-66, 81-99,
  then 108-120 seconds). After a reset the second window opens within about 63
  seconds; while disconnected the steady state is one 30-second window per ~150
  seconds (~20% nominal window duty, not a measured battery drain figure)
  instead of the former single window with no retry. Service/power-gate start,
  Bluetooth adapter ON, UI/wrist-on/Ditto settings, a new direct BLE link, and
  loss of the last direct BLE link reset the ladder; loss of the last link and
  adapter ON open a window immediately, bypassing the five-minute topology
  cooldown that still limits LAN/topology-only reopens (in practice the 120 s
  retry cap now dominates while disconnected). Adapter OFF, missing
  Bluetooth scan/connect permission, Companion ownership, identity changes and
  power suppression cancel all windows and retries. Any direct BLE link keeps
  the transport (and V4 SDK discovery) on and cancels pending retries, so other
  peers can still return; one connected peer is not treated as full recovery and
  there is no expected-peer tracking. Rejected transport writes are
  retried on the same ladder, never reported as applied. Logs and diagnostics
  record only action, reason, radio state, link count, delay and backoff level.
  Recovery of all six peers within 120 seconds is not guaranteed, and BLE-only
  six-peer recovery and battery impact still need physical acceptance testing.
  Network changes never overwrite BLE state.
- Automatic BLE recovery has a hard 30-minute budget of continuous zero direct
  BLE links (14 windows, 420 s of window time, ~23% of the budget). At exactly
  30 minutes any in-flight window or retry is cancelled, BLE discovery stops (a
  rejected SDK stop is retried, never reported as applied) and there are no
  further automatic retries. A jittered retry that would start at or past the
  budget end is not scheduled. The budget only counts while eligible (Ditto on,
  no Companion/identity change/power gate, adapter ON, Bluetooth permissions);
  ineligible time freezes it without refund, so gate, radio or LAN churn can
  neither extend nor restart it. A real direct BLE link ends the budget; losing
  the last one starts a fresh budget with an immediate window.
- After the timeout the home top metric shows a yellow **Ditto Edge Sync Offline**
  warning (user-chosen display copy; existing warning chip treatment;
  actual emergencies, CRM POOR status, physiological warnings, pending detections,
  and CRM MODERATE status take priority; no SOS, physiological or remote
  alert is created). Despite the wording, it is triggered only by this watch's
  direct (local) BLE links reaching the recovery timeout; it is not a global or
  mesh offline state, Wi-Fi/LAN sync may still be working, and diagnostics stay
  BLE-specific. Tapping it opens a compact dialog (never shown automatically)
  with the same title, no body text, and one row of actions: **Retry** (new
  30-minute budget, shortest gap, immediate window when eligible) and a back
  arrow (localized "Back" description, 48dp target) that only closes the dialog
  without changing timeout, pause or retry state. A persisted user pause (from
  an earlier build or a fail-closed unreadable record) shows the same warning.
  Neither action toggles Bluetooth, other Ditto transports, sync, trust, pairing
  or data. The warning is
  hidden while ineligible and reappears on eligibility return. Network
  Preferences shows the same warning chip so Retry stays reachable when a
  higher-priority warning covers the top metric. The shared home warning selector
  also keeps Ditto reachable without answering another domain. An open review
  remains bound to Ditto even if home priority changes, and closes when its
  warning disappears; a later warning requires another tap.
- Timeout/pause persist across service, process and reboot (DataStore) and are
  cleared only by Retry, a real direct BLE link, or explicitly disabling
  Ditto. UI, wrist-on, settings, presence, gate and adapter events never
  override them. An active budget in use is checkpointed (used time,
  elapsedRealtime, boot count) on each state and window change; a same-boot
  service/process restart charges the downtime since the checkpoint, so it
  continues rather than restarts the budget. After a reboot the used budget
  carries over without refund (reboot downtime is not charged); a spent budget
  restores as timed out without opening a window. Without boot identity a
  reboot is only detected when elapsedRealtime went backwards; otherwise the gap
  is charged as same-boot downtime (may time out early, never refunds), so
  reboot accounting is conservative, not exact. Only an absent record starts a
  fresh budget: an unreadable, malformed or unknown-version record fails closed
  as paused (safe log, no record contents) until Retry.
- Owned-point retry runs only with persistent pending ledger entries and active
  sync. Backoff is 10, 20, 40, 80, 160, then 300 seconds maximum. New publication
  intent or a successful flush resets it; no pending work means no retry timer.
  Local deletion intent also wakes the retry worker. Chat cleanup remains daily,
  never every few minutes.
- Track subscriptions include `o > cutoff OR b > cutoff - 600000`. Mapitem and
  alert use the same predicate with a 24-hour recent window, retaining recent
  expired cancellations/tombstones as well as still-live long-lived points.
  Each is closed before its hourly replacement. These are fixed cutoffs, so the
  sync window can lag by up to one refresh interval; ingestion still rejects
  stale active data at the current time. No document schemas changed. No
  geographic/team filter is applied: there is no configured mission boundary,
  and team is embedded in CoT XML rather than an indexed interoperable field.
- Self-track writes are gated by movement using TAK's accuracy-aware distance
  policy (over 15 m and over twice the combined anchor/current accuracy, both
  accuracies at most 25 m), identity/MOPP/remarks changes, meaningful vitals
  changes (5 bpm, 0.5 F, exertion 0.05, battery 5 percentage points, or vitals
  appearing/disappearing), or heartbeat at the effective TAK self-PLI interval.
  Generated vital sample clocks alone do not trigger a write. MR528/MR535's
  shared effective interval applies directly, including the 60/120/300-second
  stationary idle ladder and off-body signal; there is no parallel idle detector.
  Interval changes refresh the advertised stale
  before deferring, with the same `2 * interval + 15 seconds` stale policy as
  TAK. Explicit outbound-route-ready announcements still publish the fresh fix.
- Sync and BLE discovery pause for confirmed off-wrist, OS deep idle, exposed
  bedtime mode, battery saver, or battery at/below the existing configured
  **battery warning percentage**. This uses the app's early warning threshold
  rather than waiting for its critical alert threshold; null wrist/battery
  observations do not imply suppression. Charging or active alerting overrides
  these power gates. Wrist-on, leaving sleep/saver, or battery recovery resumes
  sync. Bedtime is observed read-only through optional Wear/OEM global keys
  `bedtime_mode` and `wearable_bedtime_mode`; devices that expose neither cannot
  report bedtime through this path (deep idle and other gates still apply).
  Ordinary screen-off/ambient display is not treated as sleep. Local storage,
  chat history and subscriptions remain intact while paused.
- Developer Preferences' **Stay connected off-wrist** switch (debug and release,
  default **OFF**) bypasses only the wrist gate for both native Ditto Edge Sync
  and TAK servers. A saved choice applies before initial sync and changes apply
  live while already off-wrist. Ditto sync, dependent BLE discovery and outbound
  publication resume only when all other gates allow; disabling restores the
  off-wrist pause. Low battery, sleep/bedtime, battery saver, manual disablement,
  Companion ownership, identity/security checks and BLE recovery limits remain
  unchanged. Sensors and location reporting keep their normal power policies.
- Outbound emergencies/alerts resume sync immediately for a renewable 60-second
  grace period, even at low battery/off-wrist; active manual alerting keeps it
  resumed beyond that period. This never bypasses Companion ownership, identity
  reconfiguration or user disablement. The Ditto route icon uses a gray
  **suppressed** slash while power-gated, not an error indicator. The later
  Network Preferences checklist merge removed MR532's home route strip; this
  indication follows its per-route checklist icon instead (visible when the
  developer's Network Preferences Checklist option is enabled).

## Mesh identity is a separate prerequisite

Matching document fields does not join different Ditto databases. All clients
need the same authorized app ID **and** matching SharedKey identity/valid offline
license credentials. Changing the app ID alone is insufficient. WearOS retains
its existing Community/Custom PDL selection. Authorized release builds receive
the ATAK-compatible Community PDL from masked, protected CI variable
`DITTO_COMMUNITY_IDENTITY`, which takes precedence over the local Gradle property.
No identity value is stored in this document, source changes, or CI command
arguments. The release job fails explicitly if that variable is missing.
Custom PDL selection remains independent. Ditto Android is pinned to `4.14.6`,
matching the inspected official plugin. Live peer interoperability still needs
authorized device testing; no device actions are part of this change.

## Native tactical identity (mapitem, alert, track)

The same plugin's CoT serializer uses `_id` for the **CoT event UID**, not `d`.
Its common metadata decorator sets `d` to the publishing device UID, `e` to
the publisher callsign, `a` to its Ditto peer key, `_v` to `2`, and `_r` to
the removal flag. Native documents do not require or emit `AuthorId`.
Track IDs happen to coincide with the device UID; independently created
markers and emergencies do not. Never collapse several events by one sender
onto `d`, or require `_id == d`.

WearTAK emits `d` and its optional `AuthorId` extension with the same publishing
device UID. For backward compatibility, old WearTAK documents with `d == _id`
still use `AuthorId` as their author. When `d != _id`, an `AuthorId` extension
must agree with `d`. Echo suppression checks the resulting author and peer;
ownership/deletion ledgers remain keyed by event UID and peer, not sender alone.

Native CoT aliases are `w` type, `g` version, `h/i/j/k/l` CE/HAE/latitude/LE/
longitude, `b/n/o` time/start/stale in Unix milliseconds, `p/q` how/access,
`r` complete `<detail>` XML, and `s/t/u/v` opex/qos/caveat/releasableTo.
The native serializer includes fields when the corresponding CoT value is
non-null; WearTAK's tactical ingress requires the point, time/start/stale, type,
version, how/access and detail fields to construct a validated event. Mapitems
add `c` (parent/link UID) and `f` (visibility). Hidden/removed documents do not
independently authorize deletion.

Sanitized native mapitem example (no WearTAK `AuthorId` extension):

```json
{
  "_id": "ff1f3217-fcfd-488f-b0fa-e95a24d05da3",
  "_v": 2, "_r": false,
  "a": "tablet-peer-key", "d": "tablet-device", "e": "TABLET",
  "b": 1792800000000, "n": 1792800000000, "o": 1792800600000,
  "g": "2.0", "w": "a-h-G", "p": "h-g-i-g-o", "q": "Undefined",
  "h": 19.4, "i": 156.933, "j": 41.879815, "k": 9999999, "l": -87.642469,
  "c": "tablet-device", "f": true,
  "r": "<detail><contact callsign='Hostile marker'/><link uid='tablet-device' type='a-f-G-U-C' relation='p-p'/></detail>"
}
```
