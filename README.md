# WearTAK-WearOS

WearTAK-WearOS is a standalone Wear OS application that connects a watch to the Team Awareness Kit (TAK) ecosystem. It is designed to collect location and on-watch sensor data, publish Cursor-on-Target (CoT) events, and provide a watch-native interface for operational awareness and alerts.

The app can connect directly to a TAK server and can optionally use SITX, Ditto mesh, BLE companion, and plugin integrations when those capabilities are configured. A phone is not required for the app's primary watch workflow.

### Map sources and 3D overlays

Open the map's top **Overlay Manager** button, then **Maps** to select
**Bing Hybrid**, **Google Hybrid**, or **OSM Vector** (the unchanged existing
OSM vector/OpenTopoMap raster composition; the label does not mean vector-only).
All three alphabetized choices remain visible under Maps.
Compact rows use 10sp labels, 14dp leading radio indicators and separate 28dp
full-width touch regions (below Android's recommended 48dp target).
Selection persists across app restarts.
Existing installations keep their original map until a provider is selected.
Camera, tactical overlays, role/team filters and saved layer choices are retained.
Original-map layer filters remain visible but disabled for hybrid imagery;
switching back to OSM Vector re-enables them with their saved visibility choices.
**3D Buildings** appears automatically in OSM Vector's tilted 3D mode from zoom 14,
and is hidden in 2D; there is no separate conflicting checkbox.
It extrudes the existing TAK `building` polygons using provider-supplied
`render_height`/`render_min_height` values, omitting missing/nonpositive heights
and `hide_3d` features. These are source rendering heights, not verified measured
building heights. No guessed height or alternate provider is added.
The bottom-right 2D/3D button on OSM Vector animates between overhead (0 degrees)
and tilted (45 degrees), preserving center, zoom and bearing. Its choice persists
across restarts and restores when switching back to OSM. Hybrid imagery stays
overhead with tilt gestures disabled and the same button greyed out/disabled.
The button hides with Buttons and while
Overlay Manager is open; OSM gestures can still adjust pitch.
Buildings use 60% opacity below tactical map layers. Tactical symbols remain
viewport-aligned; a noninteractive screen-space pass keeps self/generic dots,
route points/lines, range rings and Bloodhound lines above building depth.

Map camera center, zoom and bearing are saved when idle and on leaving the map,
then restored as the fallback on cold launch. On the first map open in a process,
a valid self location takes priority over that saved center; a delayed first fix
also centers once unless the user pans/zooms first or contact/route focus applies.
Later map opens/resumes in the same process keep the current/saved view without
snapping again. Source-specific zoom limits and the saved 2D/3D choice still apply.
An uninitialized 0,0 camera is not saved; native floating-point jitter is not
treated as navigation. Opening/resuming still requests a location/PLI refresh;
the recenter button remains explicit.

OSM tilted mode also has a prototype altitude/lollipop pass. It samples public
[AWS Terrain Tiles](https://registry.opendata.aws/terrain-tiles/) Terrarium PNGs at
zoom 13 (about 14.5 m/pixel at NYC), with an eight-tile memory cache and 32 MiB
OkHttp disk cache. Requests happen only for visible, valid-altitude targets while
the camera is idle; missing terrain is retried after a minute. Cached tiles work
offline. Missing/void terrain, unknown HAE (including `9999999`), missing geoid or
unprojectable heights retain the existing ground marker, never a fabricated stem.
Missing/void terrain or a missing/invalid geoid retains the ground marker with
no stem and no on-map failure message; diagnostic errors remain logged.
`ElevationProvider` returns orthometric meters so a DTED provider can
be preferred later without changing marker rendering; DTED reading is not yet implemented.

The bundled unmodified GeographicLib `egm96-15.pgm` is 2,076,888 bytes (1440 x 721,
15-minute grid; SHA256
`2a12f13b6df65cdea52432c7fa1b43f34b007148eb817bf032af5af710905caa`),
from [geoids-distrib/egm96-15.tar.bz2](https://sourceforge.net/projects/geographiclib/files/geoids-distrib/egm96-15.tar.bz2).
Bilinear interpolation applies `AGL = HAE - (terrain + geoidN)`. Eight independent
NGA harmonic-synthesis reference points from GeographicLib's
[GeoidHeights.dat.gz](https://sourceforge.net/projects/geographiclib/files/testdata/GeoidHeights.dat.gz)
are checked within 5 cm; the dataset's documented global maximum bilinear error
is 1.152 m, RMS 0.040 m. No GeographicLib implementation code is copied.
Terrain Tiles blend regional DEMs/vertical datums: treating them as approximate
EGM96 is a **prototype**, not survey- or floor-grade elevation. Terrain source
and dataset credits are in Credits. Tile requests disclose the visible terrain
tile area to AWS, not identities, HAE or CoT messages.

Self altitude uses `snapshotLocation`, the same effective HAE sent in outgoing
CoT (including the existing optional BLE override), only when Android Location
reports altitude present. `Location.getAltitude()` is WGS84 ellipsoidal; the app
uses the platform fused location provider but does **not** implement explicit
barometer/GPS fusion. Pressure services remain separate and unchanged. Received
CoT HAE/LE values are passed through, not smoothed or reinterpreted.

For AGL above 3 m, the marker is lifted using a perspective reconstruction from
the current MapLibre ground projection, preserving bearing, pitch and padding.
Markers and stems draw in Compose above building depth; taps follow the raised
marker. Building geometry under the actual ground point is checked, including
polygon holes and minimum height: stem portions inside a known extrusion are
dashed, outside portions solid. Unknown footprint/height means a solid stem.
Footprints refresh from rendered frames after camera idle, including tiles that
arrive after the style-load callback; an early empty query is not cached indefinitely.
No numeric AGL/floor label or altitude helper/caveat text is drawn on the map.
Indoor GPS may be wrong by 10-30 m, and reported HAE error excludes DEM/geoid uncertainty. Existing
contact/marker/alert navigation is retained. Native fixtures cover dashed indoor
stems, roof alignment, overlay visibility and compact target-menu actions.

OpenTopoMap raster stops requesting tiles at zoom 17 and overzooms instead of
showing its HTTP-200 "max zoom layer =17" placeholder. TAK vectors overzoom from
14; hybrid rasters from verified zoom 20. Camera limits are 20 for OSM and 22 for
hybrids. The existing remote contour endpoint currently returns HTTP 403 even at
lower zooms; this is not solved by zoom caps or a silent provider substitution.
The menu order is Buttons, Layers (alphabetical filters), Maps, Roles, Teams;
Roles combines the existing standard and LEO / Civil Use options without changing
their independently saved selections.
The top-right back arrow dismisses only Overlay Manager; the underlying map
back button is absent while that menu is open, and system Back dismisses it first.
The Buttons toggle also hides Channels, while Overlay Manager remains available.
Credits is the last section. Its arrow row is
collapsed by default; tap to reveal active-source copyright, source and license
links. Reopening the menu or changing source resets it to collapsed.
Required on-map notices remain visible.
If the watch has no browser, the selected URL is shown with an explicit error.

These are experimental direct online tile endpoints, not authenticated official
provider APIs. No credentials are required by these endpoints; availability does
not grant imagery rights or guarantee future access. Provider terms apply.
No bulk download or offline-pack feature is added. Normal renderer caching remains.
Provider attribution stays visible on the map. Load failures or a 20-second
loading timeout expose retry and original-map recovery in Overlay Manager.
Credits use an 8dp safe-top inset and dark text shadow without a background box;
All three providers use 6sp text; the Overlay Manager button stays at the same fixed
position below the credits on every source.
Top credits retain the short OpenStreetMap credit, SRTM and OpenTopoMap CC-BY-SA
for the unchanged original composition; see [OpenStreetMap copyright](https://www.openstreetmap.org/copyright)
and [OpenTopoMap usage requirements](https://opentopomap.org/about#verwendung).
URL formats follow the public ATAK XML references from
[ATAK-Maps](https://github.com/joshuafuller/ATAK-Maps) and
[Map Depot](https://github.com/takwerx/map-depot); no Map Depot code is incorporated.
Bing uses MapLibre Native 12.0.1's supported `{quadkey}` substitution, not ATAK's
`{$q}` placeholder. Google uses XYZ `{x}`, `{y}`, `{z}` with `lyrs=y`.
Both raster styles use 256-pixel tiles, zooms 1-20 (overzoom above 20), and retain
the bundled style's glyph endpoint for tactical labels.

### Physiological telemetry

The home metric picker is titled **Metric Shown**. Its Environmental Alerts and
Physiological Alerts split chips open their settings from the label and toggle
alerting independently from the switch. Physiological Monitoring uses the same
split-chip styling; either target changes monitoring. Both alert settings screens
put their master switch first. The Environmental Alerts switches share one saved
setting, which defaults off for a fresh install and preserves existing enabled
subtype preferences on upgrade. Turning it off gates
immersion and atmospheric-pressure alerts without changing the saved individual
switches or thresholds. Nested switches show off and are disabled while the
master is off; turning it back on restores those choices. Other alert defaults
remain off, while saved choices and physiological monitoring are unchanged.

Standalone PLI (TAK servers, multicast and direct CoT) and Ditto track `r` details
share the same `<physiology>` device block with heart rate (`hr`), skin temperature
in Fahrenheit (`skt`) and exertion (`exert`). These readings require physiological
monitoring, worn state, enabled source sensors and valid samples no older than
120 seconds or more than 30 seconds in the future, matching self-status policy.
Unavailable readings are omitted; model/UID remain for reflected-watch identity
detection. The BATDOK switch controls only the separate ATMIST additions, not
physiology. Self-status remarks are unchanged. Battery remains in `<status>` and
the shared `<physiology><device><bat>` field, independent of monitoring.
Physiology Dashboard consumers should listen for `<detail><physiology>`.
Alert/cancel metadata also uses `<physiology alert...>`, preserving its attributes
and device children. Inbound alerts accept `<physiology>` with legacy
`<biometrics>` fallback; PLI identity detection accepts both tags. Legacy JSON
schema keys remain unchanged.

Esri and Tablet Command use the same monitored snapshot and existing schema keys.
They export the same HR, skin temperature and exertion as TAK, plus the existing
opt-in daily steps/floors fields. Source disablement, monitoring off, off-wrist,
stale or invalid vital readings produce the schema's `-1` sentinels. Battery and
device identity remain independent of monitoring.

IBI, HRV, SpO2, ambient/core temperature, VO2/VO2max, sweat loss/duration, sleep
score/durations, energy score and fat/muscle mass remain `-1`, with empty timestamp
fields: they are not part of the TAK physiology contract. No new MOPP, elevation,
calorie or sample timestamp keys are added to these legacy schemas; they have no
established corresponding fields here. MOPP remains in TAK/Ditto remarks.

### While charging (opt-in)

**Developer Preferences > While charging** is one combined toggle directly
under the unchanged manual **Stay awake** switch, using the same row style.
It defaults to off in both release and debug builds. Existing installations migrate
to on if either former charging toggle was on; migration runs once and never
overwrites a later choice. When enabled,
WearTAK's running service manager starts the existing stay-awake foreground service
on external power (including a full battery), holding screen, CPU and Wi-Fi locks
plus an active Wi-Fi network request, and enables wireless debugging if the
secure settings grant is present. Charging-only mode uses Android's dim screen
wake lock immediately to reduce OLED burn-in risk, including outside WearTAK.
Unplugging, unknown power state, disabling the charging toggle, or stopping the
service manager releases this mode's demand. The saved charging choice survives
unplugging, service stops, updates and restarts; it takes effect again when WearTAK
is running on the charger. It does nothing off the charger.

The main **Stay awake** switch takes priority with a bright screen while plugged in;
disabling the charging toggle cannot stop manual mode. Manual mode still requires
external power and turns itself off on unplug, preserving its original behavior.
Turning manual mode off falls back to charging-only mode if eligible. Notification
Stop disables both choices. Neither mode changes system stay-awake or Wi-Fi radio
settings, and neither guarantees that vendor firmware preserves an ADB session.

### Wireless debugging setup for While charging

Wireless debugging is part of the **While charging** toggle, not a separate switch.
It is a developer-menu option, not a debug-build-only feature. The release
application ID is `com.aitech.weartak_civ`; the debug build adds `.dev`
in `app/build.gradle`. Without the grant, the toggle remains usable and retains
its saved choice: stay-awake still works, no ADB settings are written, and the
row subtitle says **Wireless debugging needs adb grant**. Make the one-time grant
from a trusted computer. For the signed release APK:

```text
adb shell pm grant com.aitech.weartak_civ android.permission.WRITE_SECURE_SETTINGS
```

The permission is declared in the main manifest for both build types. The
on-screen command uses `BuildConfig.APPLICATION_ID`, so a debug APK shows
`com.aitech.weartak_civ.dev` instead. Reopen the screen after granting it.
This option does not pair a computer, bypass watch authentication,
or guarantee Samsung/Wear firmware support. Settings writes and their read-back
values are logged under `WearTAK-ChargingADB`.

While the service manager runs and the shared battery source confirms external
power, this option enables `adb_enabled` only if needed and `adb_wifi_enabled`.
The same opt-in requests the existing dim-screen/CPU/Wi-Fi charging service and active
Wi-Fi network request, independently of permission to change ADB settings. It does not
change manual Stay awake semantics or turn on the Wi-Fi radio. Notification Stop
disables both manual and combined charging choices. Unplug, unknown power,
toggle-off and manager stop restore the ADB settings; no new settings are
enabled off the charger.

Before each change, a synchronous write-ahead journal saves the exact original
string (including an absent value). It survives process death; application startup
restores pending values before a new manager session can enable them again.
A manager-only restart while still plugged retains the saved originals.
Toggle-off also retries restoration even if the manager is not running.
Restoration is conditional on the current
value still being the `1` WearTAK wrote. A settings observer also relinquishes
ownership when it sees another value, even if it later returns to `1`.
Settings already enabled before this feature ran are never owned or restored.
Failed restores retain their journal for retry on the next reconciliation/start.
Android provides neither writer identity nor atomic compare-and-set for these
settings: identical writes and changes racing the final read/write cannot be
distinguished. This best-effort ownership check avoids overwriting detectable
user changes, rather than blindly restoring a snapshot.

### TAK master switch and upgrades

Multicast choice now persists across upgrades. Multicast remains on by default;
existing saved on/off values are preserved, without attributing a user choice to
legacy values. Accepted local multicast changes save an explicit-choice marker
atomically. Explicit managed policy remains authoritative without erasing that
marker; absent policy leaves the saved value unchanged.

The TAK Servers master switch defaults to on, including upgrades with no saved
servers. Accepted local switch changes are saved atomically with an explicit-choice
marker; locked/rejected changes do not create a choice. A one-time upgrade repair
restores on for unmarked legacy values. Legacy `false` has no provenance: an old
user-selected off cannot be distinguished from the automatic managed-config write,
so both recover to on. Choices saved with the marker survive subsequent upgrades.
Managed network settings remain authoritative and do not erase the saved local
choice marker. Missing restrictions leave existing switches unchanged; an explicitly
empty endpoint selection disables TAK. Invalid network restrictions reject the
update with an error log and an administrator-facing message.

In **Network Preferences**, the existing TAK Servers quick-glance switch row reuses
the main-screen TAK artwork: green/check means at least one TAK server connected,
yellow/signal means configured but switched off or not connected, and red/X means
no server configured, even when the master is on. Its text and accessibility label
describe the state independently of color; the server count and switch remain.
The main-screen aggregate transport indicator and watchface complications are
unchanged.

**Developer Preferences > Network Preferences Checklist** defaults to off and
persists independently of transport settings. It appears between Logging and
Stay awake in English. Turning it on shows the checklist in its original place
at the top of Network Preferences; turning it off hides only those five route
rows, loaded-plugin connectivity rows and the divider beneath them. Changes
take effect live. The TAK Servers detail page retains a master switch, and
loaded connectivity plugins expose Forward outbound CoT in Plugins, so hiding
the checklist cannot strand an on/off control. Other route switches remain in
their transport detail pages or Plugins.

The optional checklist at the top of **Network Preferences** gives each transport its
own icon: TAK servers, TAK SA multicast, SITX, Ditto Edge Sync and WearTAK
Companion. Ditto and Companion reuse their plugin artwork; loaded connectivity
plugins show their supplied icon when available. Icons stay visible alongside
disabled switches so each checklist entry remains recognizable. Sit(x) uses its
official cyan wordmark in both the checklist and the Sit(x) TAK settings entry,
fitted without tinting to a 32 x 20dp box. The transparent, cropped asset
(`ic_connection_sitx_logo.png`) comes from the [Sit(x) website logo](https://sitx.io/assets/sitx-logo-6ec9a693619d7ae533ccd950c26787cc8a98a533293c6ea9f1212f151bc3fd89.png);
the black background and Booz Allen tagline are removed. Existing TAK
status artwork, subtitles, switch state, lock handling and settings navigation
are preserved. This checklist does not add transport polling or change the main
screen or watchface complication indicators.

Ditto's checklist subtitle shows **Off-Wrist** when off-wrist detection alone
pauses sync, and **Suppressed** for other active power gates (including low
battery, bedtime and power save). Its switch stays on; charging or alert
overrides restore the normal identity subtitle. Disabled Ditto keeps its
identity subtitle. TAK server rows and their checklist summary already show
**Off-Wrist** during eligible off-body waiting gaps, without hiding connection
failures or relabeling Companion handoff and other route gates.

While a live phone-managed Companion bond locks network controls, their
checklist subtitles show **Bonded**, replacing any prior subtitle (or adding
one when empty). The TAK master and plugin forwarding equivalents use the
same label. A network-preferences policy lock alone does not display Bonded;
neither does a saved phone-management choice without an active managed session.
The TAK SA Multicast navigation row also shows Bonded and blocks entry while
bonded, and its detail-page switch uses the same bond/network-policy lock as
the checklist. Releasing the bond restores the saved multicast choice and its
original subtitle; the repository rejects local multicast-toggle writes while
the live bond is active.
WearTAK Companion is the exception: its checklist switch remains available
while bonded so it can turn Companion off and release phone management,
unlocking the other transports. An independent network-policy lock still blocks
this action. Companion keeps its normal subtitle, never Bonded.

If the watch's Bluetooth stack restarts or the adapter cycles, the Companion
service closes its stale GATT server and re-registers the A11A service before it
advertises again, so the phone never connects to an advertised but missing
Companion service.

On the top-level Plugins screen, the live phone-management bond disables both
actions and replaces the subtitle with **Bonded** only for these stable
`BuiltInPlugin` IDs: `DITTO` (Ditto Edge Sync), `EVERYWHERE_COMMS` (Everywhere),
`SOMEWEAR_LABS` (Somewear Labs), and `DATA_SYNC` (Data Sync).
The mapping does not use display names or match installed plugins by name.
Every other entry, including Companion and installed plugins, is unchanged.
Releasing the bond restores each original subtitle and both actions.
The Tool Preferences entry is labeled **Bloodhound**; its proximity options
and behavior are unchanged.

### Received CoT marker symbols

TAK and Companion marker ingress accept generic `a-<affiliation>-...` atom types,
not only the watch's four drop-marker types. Friendly/assumed friend (`f`, `a`),
hostile/suspect/joker/faker (`h`, `s`, `j`, `k`), neutral (`n`), and
unknown/pending/other (`u`, `p`, `o`) use the corresponding affiliation icon and
label; unrecognized affiliations use Unknown. Callsigns remain the map labels.
Existing PLI routing, coordinate bounds, source isolation, and stale/replay
handling are unchanged.

UAV and SPI symbols take precedence over generic frames. Fixed-wing, rotary-wing,
surface/boat, and ground-vehicle hierarchies use the existing platform symbols,
tinted by affiliation in marker lists and details. Cleared markers retain gray
variants. ATOS tag types still select their specific symbols and GPS-off variants
without replacing the packet's affiliation. Friendly ATOS tags retain configured
team colors; nonfriendly tags use affiliation colors.

WEARTAK-280 also accepts spot points (`b-m-p-s-m`) and circle centers (`u-d-c-c`).
Additional atom/spot/shape points use affiliation-colored dots and zoom-dependent
callsign labels (UID when no callsign is supplied); existing drop-marker, UAV,
SPI and user symbols are preserved. Platform symbols remain in marker details
and lists. A circular `<detail><shape><ellipse major="100" minor="100"/></shape></detail>`
draws a geodesic outline with a 100-meter radius. Only equal positive radii up
to 100 km are drawn; other shapes retain their center point.

Generic received map copies are capped at 512 across sources (oldest received
evicted first), expire at sender `stale` or 15 minutes without a refresh,
whichever comes first, and are swept within approximately one second. Their
navigation copies are removed too. Existing user/drop-marker lifetimes are
unchanged. `t-x-d-d` removes the event UID (or a `link relation="none"` target)
only from its originating source, including its navigation/route copy; local
watch-created points and other sources are not deleted. Event-time watermarks
reject delayed resurrection, with bounded 2,048-entry removal histories.
Chat selection/actions require a nonblank `contact endpoint`; a callsign alone
does not advertise chat capability.

Configure Ditto Edge Sync under **Preferences > Tool Preferences > Plugins**. The switch enables or disables Ditto;
tap the Ditto Edge Sync row to select Community or configure a custom PDL in Ditto Edge Sync Identity. The row subtitle shows
Community or the saved custom PDL, truncated to fit. Ditto is off by default on fresh installs, and saved
enablement settings are preserved. PDL Help provides scrollable instructions for copying and pasting a custom identity.
Outgoing standalone PLI includes a `<ditto>` detail only while the watch's enabled
Ditto peer is actively syncing. Its peer key, SDK version and configured SDK
device name come from the local watch SDK, never the phone. The detail is omitted
while stopped, power-suppressed, changing identity, or handed off to Companion.
The watch advertises the same Ditto SDK and CoT device name: `T` followed by the
last seven characters of its stable TAK UID (`WEAROS_` plus Android ID).
This matches observed working ATAK phone/tablet naming for peer association;
it is a sample-based compatibility convention, not a universal SDK contract.
Human-readable callsigns remain in CoT contact details; peer keys and SDK versions
are unchanged. Callsign changes do not change this UID-derived peer name.
Ditto pauses only while Companion is actually connected. An enabled or reconnecting
Companion service does not block Ditto, and Ditto never blocks Companion startup.
On link loss, enabled Ditto resumes; reconnecting pauses it again without changing
the saved toggle. Multicast fallback behavior is unchanged.
Ditto also requires a valid, unexpired SharedKey PDL (Community identity bundled at
build time via `dittoCommunityIdentity`, or the selected custom PDL). With no PDL,
initialization logs `Invalid PDL` with `Ditto Community license is missing`, shows
an `Invalid PDL` toast, and stops the service before sync starts. Selecting Custom
with an empty PDL does not fall back to Community. Do not commit private identities.

Ditto also syncs direct, group, and All Chat Rooms GeoChat using the `chat`
collection. Received messages use the normal chat inbox and deduplicate against
CoT transports by sender/message ID. Expired mesh messages are ignored and
locally evicted without deleting inbox history. See [the chat wire schema](DITTO_CHAT.md)
for the shared WearOS/iOS document contract and addressing rules.

Plugins are listed alphabetically, including installed plugins. Everywhere and Somewear Labs remain
unfunded placeholders. Installed plugins use split chips: use the switch to load or unload the plugin.
If a loaded plugin advertises the SDK `hostedMainUi` capability, tapping its row opens a generic host-rendered
page from its main UI schema; otherwise, tapping opens the plugin's launcher activity. Hosted pages render
SDK `STATUS`, `KEY_VALUE_LIST`, and `ACTION` cards. Actions run only after a wearer tap. Host does not
define plugin-specific controls or collect secret text input. Swipe the row to access remove and info.
Plugins without a launchable activity (or whose activity cannot be opened) reveal those actions on tap instead.
Opening an activity does not load the plugin.
Plugin authors can override the menu subtitle by placing this metadata **inside the plugin's
`<service>` element** (the service advertising `com.weartak.plugin.action.PLUGIN_SERVICE`):

```xml
<meta-data
    android:name="com.weartak.plugin.MENU_SUBTITLE"
    android:value="Unfunded Request" />
```

The host reads service metadata without requiring the plugin to be loaded. Application metadata
is not used for this override. Missing, empty, or whitespace-only values retain the loading-status
subtitle. This changes only the menu subtitle: the info popup still shows the actual loading status,
and the toggle, tap-to-open, and swipe remove/info actions are unchanged.

WearTAK Companion has a built-in split chip in Plugins and an enable switch in Network Preferences,
with the same icon as the ATAK-side WearTAK Companion plugin.
Its switch uses the existing saved enable setting and service policies. Turning
it off stops Companion BLE advertising, pending starts, refreshes, and its GATT
connection; other BLE/beacon settings are independent and unchanged. Tap the
row for connection status, phone-side connection-method guidance, and a link to
normal Android Bluetooth settings. Opening the link does not pair, reset, or
enable Bluetooth automatically. Team Lead EUD Priority is a disabled
**Not available yet** placeholder, not a priority or failover setting.
Bonded Location Diagnostics is also configured on this details page, using its
existing saved preference. It compares watch and phone GNSS quality over a bonded
Companion connection. Developer Preferences retains the diagnostic readout and
export tools, but no longer duplicates the enable switch.
Status distinguishes Off, Bluetooth off/unavailable, missing permissions, waiting,
connecting/disconnecting, ready Connected, and connection errors. Connected means
the companion notification subscription is ready, not just that a Bluetooth bond
exists. The watch does not infer Samsung-versus-Traditional mode from a bond or
device brand; choose the method in the phone companion app.

### Companion BLE discovery (WEARTAK-269)

Legacy advertising keeps the Companion service UUID
`0000A11A-0000-1000-8000-00805F9B34FB` in the primary packet. Neither packet
includes the system Bluetooth name, and WearTAK never renames the device.
The scan response contains service data under the same UUID: a version byte
`0x01`, followed by **at most 12 UTF-8 bytes** of the chosen WearTAK callsign,
truncated at a complete Unicode code point. The 12-byte budget fits the 31-byte
legacy limit even with a full 128-bit service-data UUID (2-byte AD header +
16-byte UUID + 1-byte version + 12-byte callsign). Empty callsigns send only the
version byte. The advertised callsign is always the watch's **standalone**
callsign, including while phone-managed (WEARTAK-273). Standalone callsign changes refresh an active advertisement; no GATT
disconnect or system setting change is needed.

The ATAK Companion scanner on canonical develop
`3240a0d421bc9056676d83dd60b31c1853305250` discovers watches by the service UUID,
not their names. Its existing scan-result display reads the local/device name,
not service data. To display the callsign it must read
`ScanRecord.getServiceData(new ParcelUuid(COMPANION_SERVICE_UUID))`, accept
version 1, strictly decode the remaining bytes as UTF-8, and prefer a nonblank
callsign over device name or address. Missing, malformed, or unsupported-version
data must preserve those fallbacks; primary-only results should not erase a
previously received callsign. This watch-side change does not modify the plugin.

Companion JSON envelopes (`vers: 1`) also expose the full, untruncated standalone
identity as `payload.standaloneCallsign` in `watch_info`, `settings_request`,
and the initial/changed `settings_changed` payload. The existing `watch_info.cs`
and settings `callsign` remain the effective (possibly phone-managed) identity.
For example, while managed, `watch_info.payload` includes
`{"cs":"PHONE","standaloneCallsign":"ODIN-WEARTAK"}` and settings include
`{"callsign":"PHONE","standaloneCallsign":"ODIN-WEARTAK"}` alongside their other
fields. Settings deltas include `standaloneCallsign` only initially or when that
value changes. An empty standalone value stays empty, never the phone's callsign.
The saved watch identity survives repeated managed pushes and is restored on
disconnect; this adds no device-name or bond changes.

Advertising resource/internal failures retry after 1, 2, 4, 8, and 16 seconds
(five retries maximum), with named errors in logcat. Oversized data, unsupported
features, permission failures, and unknown failures stop without retrying.
Success resets the retry budget. After a terminal failure, resolve the cause and
restart Companion; BLE-state notifications cannot bypass the retry/terminal gate.

### Live Companion identity commands (WEARTAK-276)

A phone-managed Companion can update one effective identity field at a time:

```json
{"vers":1,"msgType":"set_callsign","payload":{"callsign":"ALPHA-1"}}
{"vers":1,"msgType":"set_team","payload":{"team":"Red"}}
{"vers":1,"msgType":"set_role","payload":{"role":"Medic"}}
```

Each payload must contain exactly its one string field. These use the same
validation and identity apply logic as chat `setprofile`/per-field commands:
callsigns are trimmed, 1-32 characters (letters, digits, spaces, `.`, `_`, `-`);
team and role names match the configured lists case-insensitively, and a role
also selects its category. Blank, invalid, null, wrong-type, or extra fields
fail without changing anything. Only the current bonded, subscribed peer that
owns an already active phone-managed profile may send them. Session generations,
management release, disconnects, and Gateway mode fence queued writes.

The response is the existing version-1 `settings_set_ack` envelope, with
`payload: {"ok":true}` on success or `payload: {"ok":false,"error":"..."}` on
failure. Standard `msgUid`, `t`, and `src:"wearos"` metadata remain present;
ended/unready sessions cannot receive an ack. Normal `settings_changed` deltas
also report changes. Identity changes refresh runtime display state and request
PLI through the existing identity-refresh path.

Servers, certificates, reporting interval, other preferences, and the saved
standalone identity/snapshot remain untouched. Chat identity commands still
refuse edits while the live phone-management lock is active. `set_identity`
and `setdittopdl` are not added; older watches ignore these new message types.

### Continuous Companion advertising (WEARTAK-270)

Confirmed idle advertising stays in one continuous, connectable **BALANCED**
registration. There is no 30-second refresh or two-minute mode-switch restart.
Stopping and recreating a legacy advertisement can give it a new private address,
making a phone's recently scanned entry stale during selection or bonding.
The former timer was a workaround for apparently silent advertising termination;
it is not a reliable health check and must not restart healthy advertising.

A 30-second watchdog applies only while awaiting the start callback. An unanswered
start is cancelled and uses the same five-retry backoff budget as an internal
failure. Success or `ALREADY_STARTED` cancels the watchdog. Explicit connection
errors/disconnects still recover through the existing GATT lifecycle; changing the
encoded callsign still refreshes the scan response. No device settings or bonds
are changed.

Android controls BLE private-address rotation; this does not pin an address or
promise a permanent hardware identity. Phone scan lists should expire stale
last-seen entries for natural privacy rotation or actual service restarts, rather
than merge watches by callsign (which may be shared or truncated). Legacy
advertising has no reliable callback for a silently terminated confirmed set;
this change deliberately removes speculative periodic recovery in favor of
observed start failures and connection lifecycle events.

### Bonded phone transport (WEARTAK-272)

An active bonded Companion BLE connection carries both inbound and outbound CoT
through the phone only. The transport becomes active after the bonded peer
subscribes to notifications, not merely because a bond or saved phone-managed
profile exists. Direct multicast receive/publish and TAK-server connections stop;
late direct packets and queued TAK writes are gated before delivery. SITX and
Ditto pause without changing their saved enable preferences, and direct plugin
CoT is excluded while the bonded phone transport is active. BLE CoT continues
through its existing authenticated receive path.

Notification unsubscribe, disconnect, bond loss, connection error, or Companion
shutdown immediately clears the runtime transport gate. Configured direct
networking then resumes automatically, even when the phone remains bonded or
phone-managed settings remain saved. Reconnecting and subscribing switches back
to phone-only routing. Standalone route selection and configured service
preferences are unchanged; this does not unpair or rename any device.

Commands uses original WearTAK megaphone artwork (`ic_commands_megaphone.xml`), not a vendor mark.
Commands retains **Unfunded Request** in the plugin menu; Data Sync shows its real enabled/paused state.
Opening Commands shows informational, wrapping command examples beneath Allowed Callsigns in the scrollable settings list.
The five branded integrations use the artwork below, fitted proportionally into 24 dp without theme tint.
The user confirmed permission to use these identified assets on 2026-10-21; this is user-confirmed
permission, not a claim of a public redistribution license. Only Ditto is the exact ATAK plugin icon;
the other marks are approved official-company backups, not claimed as ATAK plugin artwork.

| Integration | Artwork and provenance | Preparation |
| --- | --- | --- |
| Somewear Labs | [Official 192 px symbol](https://somewearlabs.com/wp-content/uploads/2026/07/cropped-somewear-favicon-rounded-corners-192x192.png) | Unchanged PNG, `ic_integration_somewear.png`. |
| Everywhere | [Official white logo](https://everywherecomms.com/cdn/shop/files/everywhere_logo_white.png?v=1727986047) | Left 232 x 232 globe crop from the 1517 x 232 logo, proportionally reduced to 192 px; no redrawing. `ic_integration_everywhere.png`. |
| DART and FALCON | [Official Certus Innovations symbol](https://images.squarespace-cdn.com/content/v1/687cf63b5a78985697612a02/8c2c76c6-79ac-4cf4-8dca-a3e9f8f0293c/CI-Icon-Arrow_white%2Bteal.png) | 1920 px artwork proportionally reduced to 192 px, retaining white/teal and transparency. Both use `ic_integration_certus.png`; labels distinguish the products. Product identity: [DART](https://www.certusinnovations.com/products/dart), [FALCON](https://www.certusinnovations.com/products/falcon). |
| Ditto Edge Sync | Installed `com.dittofederal.atak.plugin.ditto` APK, `drawable/ic_ditto_menu` (`0x7f07006b`), compiled `res/AC.xml` | `ic_integration_ditto.xml` preserves all four original paths and `#F6F6F6` fills. Transparent margins trimmed from the 376 x 416 viewport to the artwork bounds (284 x 324), with proportional intrinsic size. |

Sensor Manager displays **Unfunded Request** and has a saved enable/disable preference (off by default). Tap its row to open the preview
configuration showing **Unfunded Request**, **Button is Placeholder**, and a single inert **Scan** button.
The future workflow will list discovered sensors with per-sensor Pair controls and change the main action
to Unpair once paired. Enabling Sensor Manager does not start scanning or connect hardware yet.
Its row uses an original neutral sensor/radio-wave glyph (`ic_sensor_manager.xml`).
CRM uses an original neutral gauge (`ic_plugin_gauge.xml`); neither is a vendor logo
or a claim of working hardware. Every built-in plugin now has an explicit icon
mapping; the existing Companion mark, Commands megaphone, integration artwork,
MOPP mask and independent Data Sync icon are retained. Icons do not enable an
integration or change its saved state, navigation or placeholder status.

## What it does

- Connects to TAK endpoints and sends and receives CoT traffic.
- Publishes position/location information (PLI), markers, SOS/manual alerts, sensor-driven alerts, and TAK chat payloads.
- Collects health, activity, and environmental data, including heart rate, location, activity, skin temperature (where supported), BLE beacons, pressure, and off-body state.
- Provides Wear OS activities, a Tile, and watch-face complications for common operations and status.
- Supports managed configuration, allowing an enterprise mobility/MDM provider to apply settings and service enablement.
- Includes a configurable complex-event-processing (CEP) engine for evaluating sensor/event streams and dispatching alerts.
- Provides optional audio detection pipelines for gunshot and UAS events, and optional CRM inference.
- Hosts supported WearTAK plugins through the included plugin SDK contract.

Some capabilities depend on hardware, permissions, credentials, configuration, and separately provisioned services or SDKs. In particular, mesh, companion, SITX, plugin, audio-detection, and managed-configuration features are optional.

### Ditto receive support and deletion boundary

With Ditto running, SDK **4.10.2** store observers consume initial local snapshots
(including reconnect/restart snapshots) and subsequent changes, not just sync
subscriptions. They reconstruct CoT from the v2 aliases already written by this
watch and use `TakServerCotIngestor` and the existing application handlers:

| Collection | Supported data | Application destination |
| --- | --- | --- |
| `track` | Supported PLI types | Source-aware received-user map state |
| `mapitem` | Supported PLI, GeoChat, emergency, point and `b-m-r` route CoT | Map, ChatService inbox, remote alerts/Bloodhound, route repository/UI |
| `alert` | Supported emergency CoT, including cancellation | Existing remote-alert/Bloodhound handler |

The supported document shape is `_v=2`, `_r=false`, a string `_id` equal to `d`
(event UID), string `a` (peer key), `g` (CoT version), `w` (CoT type), `p` (how),
`q` (access), and `r` (one complete `<detail>...</detail>` element). `AuthorId`
is optional. `b`, `n`, `o` are integral epoch milliseconds (time, start, stale);
`h`, `i`, `j`, `k`, `l` are finite numeric CE, HAE, latitude, LE, longitude.
Latitude/longitude must be in range. Unsupported versions, types, raw-XML-only
documents, missing aliases, malformed XML, DTDs/entities, and invalid timestamps
are rejected with diagnostics; there is no fallback parser. Compatibility with
other producers is limited to this shape, not a claim of general ATAK Ditto support.

Ditto connectivity/presence alone does not provide a map position: a peer must
publish a supported, current PLI document. Received users still obey **Layers >
Users**, team-color and role filters. Standard and LEO role selections are saved
independently, so editing one section no longer hides the other after reopening
the map. For the old shared role preference, a nonempty selection containing only
one section's roles is retained for that section; the overwritten other section
uses its defaults. Old empty, mixed or unrecognized selections are preserved
conservatively. New saved selections, including an empty section, take precedence.
Missing or unrecognized team/role metadata remains subject to the existing filters.

Maximum reconstructed CoT is **256 KiB UTF-8**; maximum snapshot is **4,096
documents** and **2,097,152 detail characters** per collection. An oversized
snapshot is rejected as a whole. SDK next-snapshot signalling supplies
backpressure while ingestion is serialized. Native results and observer handles
are closed, queued work is cancelled on stop, and late callbacks are fenced.
An exception in one document does not discard other valid documents.

Live events over five minutes in the future are rejected. A private replay journal
stores hashed peer/UID keys, event times, stale times and SHA-256 payload digests,
not message bodies. It suppresses older revisions, repeated snapshots and
track/mapitem duplicates from the same peer; conflicting equal-time revisions
are first-accepted-wins. Expired input cannot introduce live points/routes.
Receiving an expired revision removes only the matching source's copy, never
another transport's copy. Routes expire at their original CoT stale time.
At restart, equal current live revisions can rehydrate the repositories; already
recorded chat documents are not re-notified or used as historical inbox restoration.
The journal holds **8,192 identities**; entries whose previously accepted
lifetimes have all expired are pruned when full,
otherwise new identities are rejected with diagnostics rather than silently
evicting replay protection. No exactly-once delivery guarantee is implied.

Replay retention covers **observed** lifetimes, not arbitrary unseen revisions.
After an identity's journal entry is pruned, an older still-synced PLI/route
document with an unobserved, longer stale time can be accepted again after
restart. Ordinary expired documents cannot become live again. Point ordering
and terminal deletion use the separate non-pruning ledger described below;
the time-bounded receive journal does not authorize deletion.

Repository/event delivery happens **before** the replay-journal commit. A failed
commit emits an explicit diagnostic but does not roll back already delivered
state; later documents continue. A crash before commit can replay the event
(the ChatService inbox merge still deduplicates by message identity when that
inbox survives). A crash after commit can rehydrate equal live point/PLI/route
state from the next snapshot, but recorded chat is suppressed rather than
restored into a fresh in-memory inbox. There is no atomic durable inbox/journal
transaction. Lint's `UseKtx` suggestion is style-only; explicit `commit()` and
its checked Boolean result are intentional.

Recipient/group membership, self-chat filtering and chat enablement use the shared
chat parser/settings. Local peer/author/UID echoes and locally owned/deleted points
are suppressed. Receive callbacks do not dispatch or relay inbound CoT to TAK,
companion, Ditto, multicast or plugins. This is intentionally **data-only**:
command text can appear as ordinary addressed chat, but no command runtime,
configuration/plugin control, geofence, channel-change or data-package handler is
installed. An active Companion connection pauses direct Ditto; a reconnecting
Companion service alone does not.

Across TAK server, multicast and Ditto ingress, PLI and point repositories retain
each source-owned copy but expose one logical item per event UID. Newer event
times win; an older arrival cannot replace a newer visible copy. Losing a source
can reveal another source's copy without creating a second entity. Source
metadata may legitimately change; secondary ingress is not discarded.
Bloodhound registers those copies without emitting a second unread/notification
event for the same UID and event time. Emergency cancellation remains
UID/version scoped across sources, so a delayed active revision cannot undo it.

GeoChat history uses sender UID plus `messageId`, independent of transport.
The same message produces one history entry/unread message; distinct message IDs
remain distinct even when their text and timestamps match. Legacy chat without
an ID retains the existing sender/time/text fallback and its inherent ambiguity.
These paths do not add a new operating-system notification mechanism.

CoT routes with an event `time` retain their source-owned copies and expose the
newest revision per UID. Identical copies do not duplicate route rows, and
identical-source fallback preserves active route navigation. Mission bookkeeping
still sees every source copy. Legacy routes lacking an event time keep their
existing source-specific identity rather than guessing a revision from geometry;
they cannot provide the same cross-transport version guarantee. Producers must
preserve the original UID/message ID and advance event time for updates.
Different UIDs are not merged merely because coordinates or text match. This
prevents local duplicate presentation, not loops or rewritten identities
introduced by external peers.

### WearTAK own-created-point deletion

**Trust model:** all members provisioned with the shared Ditto mission key are
trusted not to forge point ownership or deletion fields. `AuthorId`, peer alias
`a`, and revision hashes are writable data, **not authenticated individual
authorship**. A malicious provisioned writer can forge them. This is a
WearTAK-to-WearTAK cooperative contract, not an ATAK deletion interoperability
claim or hostile-peer protection.

The watch records local drop creation before adding the point to application
state; imports and received points cannot acquire local publication ownership by
claiming `AuthorId`. A supported point publication records its exact document
and revision before writing to Ditto. Local clear persists a terminal intent
before local removal, then the running Ditto service retries its pending store
write every ten seconds, including after service/process restart. Clearing an
unpublished or pre-ledger point remains local/eligible-companion only. There is
no new remote-delivery success status: a completed local clear or SDK store write
does not prove any peer received it. Existing companion-over-Ditto precedence
can defer mesh publication until Ditto runs.

The original `mapitem` keeps its `_id`, `d`, `a`, `AuthorId`, base timing,
coordinates, type and detail aliases. Live WearTAK-owned points add:

```json
"weartakPoint": {
  "version": 1,
  "owner": "<AuthorId>",
  "revision": "<SHA-256 of reconstructed live CoT>",
  "deleted": false
}
```

Clear updates **that same document** to `_r=true`, `visible=false`, `f=false`,
sets `weartakPoint.deleted=true`, and adds integral epoch-millisecond
`deletedAt >= b`. The revision still identifies the original live payload;
`deletedAt` is informational, not an ordering override. A tombstone can arrive
before its live revision, and remains meaningful after the original stale time.
Plain `_r=true` without this validated contract, hidden documents, query
disappearance, local eviction, and CoT `t-x-d-d` do not authorize deletion.
No SDK document removal, eviction or collection delete is used.

Receivers pin source/UID ownership and event-time/revision ordering before
mutating repositories. A valid tombstone removes only the matching Ditto
map/Bloodhound source copy, never watch-local, another peer's, TAK, multicast or
companion copies. Cross-owner, older and conflicting equal-time revisions are
rejected. Legacy points without an author cannot later be upgraded into
author-owned deletion authority. A deleted source/UID is **terminal**: repeated
deletion is idempotent; even a newer live revision cannot resurrect it. Recreating
a deleted point requires a new UID. Local origins are also never reused, and
pending writes are not replayed into a different native local-peer store.

Local ownership/pending publication records use app-private
`ditto-local-points-v1` preferences, including the retained point payload.
Limits are **4,096 local origins**, **384 KiB per serialized record**, and
**8 MiB total**. `ditto-point-watermarks-v1` holds **8,192 source/UID records**,
**8 MiB total**, separately from ordinary receive dedup. Neither point ledger
prunes or evicts records. Capacity exhaustion explicitly rejects new drops or
new inbound point identities rather than losing ownership/replay protection.
Deleting an already recorded point does not require another identity slot.

Checked persistence failures are logged and fail closed: failed local intent
keeps the point/clear queue, and failed inbound watermark storage cannot remove
the repository copy. Failed store writes or write-ack persistence keep a pending
publication for idempotent retry. An unacknowledged in-memory preference change
is repaired before another successful ledger write. A crash after durable
tombstone storage but before repository mutation is completed by replaying the
retained tombstone snapshot; a crash after store write but before acknowledgment
may repeat the same write. There is no atomic SDK/preferences/repository
transaction. The existing local dropped-marker UI list itself remains
in-memory; these ledgers provide ownership and publication recovery, not a new
point-history restoration UI. Clearing app storage or a failed storage device
loses these guarantees.

Local observer tests use mocked SDK callbacks and synthetic data. Native Android
SDK/peer interoperability and over-the-air receipt still require a separate,
authorized hardware check; passing these tests does not prove mesh delivery.

### PLI metadata compatibility

Watch-built PLI XML and Ditto track details include `takv`, `status`, and `__group`
as immediate children of `detail`. `takv` identifies WearTAK using the installed
app's build version, Android SDK level, and actual watch manufacturer/model, not
phone or ATAK metadata. Callsign, UID, team, role, and device strings are XML-escaped.
Structured `status battery` is emitted only for an available percentage in 0..100;
biometrics and BATDOK details retain their existing formats.

Standalone TAK/multicast PLI and Ditto track `r` XML use the same remarks line as
Companion `WatchSelfStatus.Contribution.remarksLine` (canonical Companion develop
`58fe25f1ef1e8241c02f7a37c43fc962800b0eac`). For example:
`MOPP0; HR: 79 bpm @ 03:17:19Z; Skin: 91.1F @ 03:17:18Z; Exert: 16% @ 03:17:19Z`.
MOPP comes from the same live-plugin saved state as `self_status.ppe.level`; `None`
and unset/unavailable MOPP contribute no token. Physio uses the same monitoring,
on-wrist, sensor opt-in, plausibility and actual-sample freshness policy as
Companion self status. Unavailable metrics are omitted, not replaced with `N/A`;
an empty contribution produces empty remarks. Skin uses one US-locale decimal,
exertion is rounded to an integer percent, and clocks are actual sample times
in UTC (not PLI publication times). Ditto no longer adds battery to remarks;
its structured battery fields are unchanged.

Direct XML routes and the negotiated raw-XML companion gateway preserve those
fields. The legacy companion PLI JSON envelope is unchanged: it includes battery
and callsign but not team, role, or `takv`. Separate legacy settings snapshots and
`settings_changed` deltas carry team/role. The initial delta includes both; later
deltas include only changed fields, including team-only or role-only changes,
without changing the envelope version or notification queue.
Legacy phone-side PLI reconstruction still uses the phone/EUD identity and location;
these settings messages do not make it watch-source PLI or supply watch `takv`.
Companion observation/conversion requires a separate phone-side update. Passing
local tests does not establish phone consumption or server/ATAK receipt.

### Map rotary bezel zoom

On the map, rotate the bezel clockwise to zoom in and counterclockwise to zoom
out. Rotary input falls through to normal handling while Layers, Channels,
own-location details, the overlapping-target picker, route details, or route-point
details are open, and while navigating to the selected-user menu. Zoom resumes
when the unobstructed map is active again.

### Map layer visibility

Overlay Manager preserves saved visibility choices without reloading the native
map style. Hidden base layers are replaced individually by invisible layers and
restored when shown again; marker/contact/route overlays and the camera retain
their current ownership. This avoids invalidating queued overlay filter updates
when toggling layers. Unknown layer IDs from older saved settings are ignored,
not deleted.

The immutable base style is scoped to each map composition. Returning to a
retained map keeps its live style; recreating map content creates fresh overlay
wrappers. Do not reintroduce in-place whole-style replacement while overlays are
live: the pinned MapLibre Compose library can apply an already queued filter
after that old native style has been cleared. Lifecycle regression tests cover
stop/resume, destruction, navigation and recreation with 74 synthetic feature
records and mocked native calls; they do not establish physical-device rendering
or crash acceptance.

### WEARTAK-121: Bloodhound to a map user

From Home, tap the compass/Bloodhound target to open the picker, including while
guidance is active. Only the decorative target glyph beside the active target title
is omitted; the rotating compass, target/distance, unread marker, picker shortcut,
Map button and clock remain. The picker row's red square stops guidance without deleting
the target; **Remove** removes that row. **nPos** means **In Position**: it completes
the ordinary assignment, removes its Bloodhound row and stops its guidance. A chat
reply is sent only when the assignment has an identified issuer; local pins do not
invent a recipient. This does not delete the dropped pin itself.

On the pin-drop screen, hold **X** to open the marker menu, then **Dropped Markers**
to inspect or delete saved pins. Moving a local pin that is being Bloodhounded
updates its guidance destination without restarting tracking; unrelated pins do
not change the selected target.

Pin names and full one-meter MGRS fit on a single line. Pin details display
latitude/longitude to five decimals without changing saved coordinate precision.
The dropped-marker list has one scroll owner, a high-contrast heading and an
explicit **No Dropped Markers** state; Back returns to the existing marker menu.
The home callsign similarly fits its complete text rather than ellipsizing.

Tapping your own location dot opens a centered three-row popup: **Self**, signed
latitude/longitude with five decimal places on one line, then full one-meter MGRS
calculated from the original unrounded position. Coordinate rows fit without
wrapping or truncation; accessibility names latitude and longitude. This
identification is map-only; the dashboard coordinate display is unchanged.

Nearby visible contacts and pins (within 28 dp of a group anchor on the current
map projection) suppress overlapping labels; no target-count label is drawn.
Tap any member icon to
open a native scrollable **Select Target** list and choose the contact, dropped
pin or received marker. Zooming recalculates groups. All original icon coordinates,
identities, visibility filters and stored precision remain unchanged. No target
is offset, deleted or converted to historical copies.

Tap a user on the map to show one compact row of existing icons: **Bloodhound**,
**Chat**, then **Target**. Bloodhound starts tracking and returns to the map.
Target centers on the selected contact at zoom 17, then preserves subsequent
manual navigation rather than repeatedly snapping back. The Bloodhound action uses the
selected user's UID and location; subsequent PLI from that same source refreshes
the target while preserving tracking state. Users without a valid position cannot
be selected for Bloodhound. If the user disappears, the last known target remains
until stopped or removed through the existing Bloodhound controls. This action is
also available on the map opened from Chat: it starts Bloodhound and returns to
that map without selecting a chat contact or sending a message. **Open Chat**
retains the chat picker's existing contact-selection behavior.
The icons use 22 dp artwork with 48 dp touch targets, including on small round
screens. Bloodhound and Target are disabled without a valid contact position;
Chat retains its existing endpoint validation. The ordinary Bloodhound detail action
also reads **Start Bloodhound** instead of **RGR**; acknowledgement and stop behavior
are unchanged.

Routes, route points, received map points and saved pins use the same compact
**Bloodhound / Target** row without Chat. Whole-route Bloodhound opens the existing
nearest/beginning start chooser; Target frames the entire route. Point Target
centers at zoom 17. Active point targets retain their existing **nPos** acknowledgement.
Invalid coordinates disable both actions. Other point-edit and route-removal
controls remain available.
Saved pins and received points append a trash icon after Target, replacing their
old Delete text controls. Saved pins retain their existing confirmation; received
points retain their existing immediate local deletion. Delete remains available
when navigation is disabled by a missing position. Route points have no existing
individual-delete flow, so omit trash. Whole routes now append the same trash
icon, replacing their Remove text control and keeping the existing confirmation.
Route removal remains watch-local, including mission routes; it does not delete
server mission data. The current route model has no separate read-only flag.

Bloodhound applies each heading sample to source-owned targets in one batch,
publishing only the completed compass state. Unchanged samples do not rebuild
the target projections. This does not change heading sampling, background
guidance, proximity alerts, location acquisition or reporting intervals.

All-target distance/bearing calculations and picker sorting run only while the
picker is visible. A visible detail page projects only its own target; otherwise
guidance still updates the active target for navigation, proximity and Companion.
Stopped/retained pages release their demand, and opening a page or changing the
active target immediately recomputes using the current location and heading.
Incoming targets and their source timestamps continue updating offscreen.
Dashboard collection follows its visible lifecycle. Interactive map/marker views
and installed compass complications remain heading consumers without an active
target; active guidance also owns background heading. The listener-demand and
cached-ambient behavior is described below, independently of reporting policy.

## Performance and heading demand

Routine foreground services share one silent, ongoing **WearTAK active**
notification. Its count and expanded capability list describe foreground service
ownership, not successful server connections, current sensor readings or message
delivery. Tap it to open WearTAK for current status. Each service still calls
Android's foreground API with its existing manifest type and permissions.
Membership changes update the card without polling; stopping one service keeps
the others visible, and the last owner removes it. Process startup removes known
legacy routine cards left behind by the old detached-notification lifecycle.

Microphone capture, Stay awake and simulation controls retain separate foreground
notifications. AltBeacon's supported scanner foreground notification also remains
separate. Enrollment prompts, test alerts and all existing message, urgent,
physiological and proximity alert behavior are unchanged. Simulation test alerts
have their own reserved ID rather than overwriting Companion's former status ID.

Incoming CoT parsing reuses thread-safe compiled attribute patterns keyed only by
the parser's fixed schema names, never incoming XML or attribute values.
The ingestor also reuses each immutable message's type/chat classification.
These changes avoid repeated pattern compilation and scans without suppressing
messages, changing parser acceptance or altering command/plugin/transport order.
They do not change logging policy, location acquisition or reporting cadence.

Ditto document verbose-log messages format their payload only when Android's
existing tag/level check enables `VERBOSE` for `WearTAK-DittoService`. Disabled
verbose logging no longer stringifies whole documents. Enabled verbose messages,
warnings/errors, the opt-in Logging journal and all publication/retry/gating
behavior remain unchanged.

TAK reader `DEBUG` diagnostics likewise compute ingress metadata, active-channel
projections, payload hashes and UTF-8 formatting only when Android enables
`DEBUG` for `WearTAK-TAKConnectService`. They are logging-only, not protocol,
authorization or deduplication inputs. Enabled receipt/routing messages keep
their content and ordering; disabled diagnostics retain no per-frame metadata.
Ingest ordering, warnings/errors and opt-in typed Logging events are unchanged.

With the existing **Logging** consent enabled, `PROCESSING_WORK` records aggregate
completed work in six fixed stages (`KIND`): TCP socket reads (0), framing (1),
TCP ingest (2), plugin offers (3), user-store/projection (4) and marker-store/
projection (5). Sink stages include their other existing direct-CoT entry paths;
these are not per-server or per-peer counters. Numeric fields contain operation
`COUNT`, socket `BYTES`, produced `FRAMES`, total/max `DURATION_US`/
`MAX_DURATION_US`, `FAILURES` and `WINDOW_MS`, plus `COALESCED` and `SKIPPED`
projection counts for user/marker stores. Reduction-only records may have zero
operation `COUNT`; these are not counts of lost network messages. Socket bytes are actual decrypted
application-stream reads, including framing separators, not encoded-frame size
or cellular/TLS overhead. Exceptions/cancellation propagate unchanged and count
as incomplete operations. Durations are inclusive elapsed time (including I/O,
suspension and nested stages), not CPU time; do not sum them as CPU usage.
Parsing/dispatch time is not separately isolated from the inclusive ingest stage.

Fixed-size numeric accumulators emit at most once per stage per 30-second
callback-driven window, with a partial-window flush on export, service shutdown
or Logging disable. There is no timer, extra subscription, transmission or
per-frame journal write. Disabled Logging skips timing and result selectors.
No payload, fingerprint, channel, endpoint or contact identity enters these
aggregates. They can locate workload, but cannot prove a specific sender's
end-to-end delivery. Message acceptance, plugin offers and transport fanout are unchanged.

Inbound source-identified, machine-generated tracks/PLI without assignment/remark
or urgent detail use bounded 1.5-second repository projection windows:
new source/UID copies appear immediately; repeated safe updates retain the latest
copy per source/UID and publish one completed repository/UI snapshot for the window.
Continuous traffic does not restart the timer. Event-time/receipt/insertion ordering,
source provenance, stale checks and deletion watermarks commit immediately, before
projection batching. There is no queued raw ingress to replay after a transport gate,
identity/session change, source clear or delete. Clears, deletes, local/user actions,
chat, alerts/emergency, human-generated points, plugin injections and assignments remain immediate;
an immediate mutation also flushes any pending projection. Emergency/alert-bearing
`a-*` details are explicitly excluded from batching.
Cancelling the projection scope flushes accepted state and clears pending keys;
subsequent updates on a cancelled scope publish immediately instead of becoming stranded.

No-op projection suppression compares position and all stored attributes, retaining
authoritative timing/order metadata even when no UI write is needed. Expiry changes
are not no-ops, and a cross-source winner change is never hidden. `COALESCED` counts
accepted replacements of an already-pending source/UID; `SKIPPED` counts unchanged
safe projections, not rejected older events. Both counters follow existing Logging
consent and aggregate/export/reset behavior. This reduces projection work only;
XML parsing, admission and safety-sensitive handling are not delayed. No device
performance or battery improvement is claimed without a separate physical trial.

Native location requests still start independently for scheduled observation
and explicit interactions, with their existing six-second and five-second
deadlines. An already-arrived usable fix may complete other active consumers
only after each passes its own selected-provider/input-generation, request
fix-age, deadline and latest-action checks. No initial request waits for or
skips another request. Completion removes only the eligible one-shot owners;
periodic ownership and original timeout deadlines remain intact. Cancelled
callbacks cannot deliver to replacement requests.
Each fully identical platform `Location` is stored once and produces at most
one normal location notification; richer same-timestamp fixes remain distinct.
Explicit interaction reasons still produce their own bounded refresh event,
not a periodic-cadence substitute. Only the last completed/normal fix snapshots
are retained in memory and cleared on request-generation reset; no location
contents enter the Logging journal. This reuses fix completion, not proof of
reduced native GPS acquisitions or measured energy savings.

Scheduled one-shot observations back off after three consecutive timeouts or
acquisition failures: the normal interval doubles on the third failure, doubles
again on each subsequent failure, and caps at five minutes (a configured interval
longer than five minutes is never shortened). Usable fixes, detected movement,
explicit interaction refreshes, configuration and power-policy changes reset the
failure streak. Cancellation, stale callbacks and permission gates do not count
as acquisition failures. Logging records numeric `BACKOFF_LEVEL` on requests and
observation schedules, plus fused request `PRIORITY` (high=100, balanced=102)
and `MAX_AGE_MS` (0 or 30000). This changes acquisition cadence, not saved PLI rates.

When existing valid speed or enabled, permitted activity recognition indicates
stationary, scheduled fused observations use Balanced accuracy, allow fixes up to
30 seconds before the request, and do not wait for accurate location. Recognized
movement overrides stationary speed; unknown state does not imply stationary.
Moving/unknown and explicit interaction requests use high accuracy and require
a post-request fix. Routine observations retain the idle safety-net cadence described
under Reporting below. Selected native GNSS
and network inputs retain their provider contracts, without fallback. Cached
fixes retain their actual timestamps and accuracy (including CoT `ce`) and do not
renew an old speed's full validity window. These are acquisition-policy changes,
not evidence of measured energy savings.

Rotation-sensor acquisition is demand-driven, not implied by the core movement
service running. Interactive dashboard/map/marker and Bloodhound views use their
existing lifecycle-owned heading subscriptions; an idle Bloodhound service no
longer keeps a heading subscription. Installed compass complication slots retain
independent ownership across process restart and removal, and keep the fused
heading projection subscribed while interactive. Existing subscribed-flow
cleanup delays still apply after the final view closes.
Display-off and noninteractive ambient/AOD compass rendering use the last cached
bearing, **not live heading**. Rotation resumes on interactive demand, with
smoothing reset and pre-registration callbacks rejected. Active Bloodhound
guidance remains a background consumer regardless of screen state, preserving
navigation/proximity/Companion behavior. No location/report acquisition,
permissions, sensor sampling rate, or complication update cadence is changed.
Logging uses distinct `DISPLAY_INTERACTIVE` and `ROTATION_LISTENER` events from
these existing hooks. `ACTIVE` is 1 for interactive or registered and 0 otherwise;
rotation `RESULT` is 1 for successful registration/removal and 0 for a failed
registration. Initial interactive state and subsequent changes are recorded;
duplicate screen broadcasts do not create changes. Noninteractive does not
distinguish display-off from ambient/AOD, and a service request is not proof of
registration. Shutdown records actual removal. These numeric events use the
existing five-second aggregation and privacy/storage bounds, not individual
samples or a measured energy saving.

## GitHub pipeline release

Protected `main` push pipelines publish the signed WearOS APK only to the rolling
**TAK.gov Forge CI/CD Pipeline Release** in the public
[`aegorsuch/weartak-wearos`](https://github.com/aegorsuch/weartak-wearos) repository.
Project policy treats these as stable builds, not prereleases. See
[CI release setup and retention](ci/README.md) for pipeline setup and safeguards.
Existing company Releases and tags remain untouched. The publisher first fast-forwards
personal `main` to the exact canonical
pipeline source, then publishes the same verified signed APK using a separate
repository-scoped CI credential. It does not build or sign independently. The CI
documentation covers required administrator configuration, provenance and verified replacement behavior.
Other numbered releases and unrelated assets are preserved.

## Platform and requirements

### WEARTAK-169: Bluetooth name is no longer changed

WearTAK no longer changes the watch's system Bluetooth name at startup, when applying
a callsign from the phone, or when restoring the watch's own settings. Companion BLE
discovery still uses the A11A service UUID, and advertising still includes the current
system device name; no `WT-` prefix is required. Bluetooth connection permissions remain
necessary for GATT and bond handling.

Callsigns are exchanged independently in `watch_info.payload.cs`, not inferred from
the system Bluetooth name.

An existing name such as `WT-ODIN-ATAK` is not automatically restored because the original
name is unknown. Rename the watch manually in watch Settings if desired.

### Companion bond retention

Transient missing-key, failed BLE setup, and pre-session disconnect retries retain
the Android system bond and the matching phone-managed settings. The watch never
invokes `removeBond` during a retry. An active-peer missing-key event resets transport
readiness, RX/queued work, and session state before advertising again; unrelated-peer
events cannot reset the current session. Retained pairing is not message authorization:
current-peer and bonded-device checks still reject stale/unbonded writes.

A genuine Android `BOND_NONE` transition releases management only through the
repository's matching-peer operation; an active unpair also resets BLE. Startup still
checks whether the managing peer is actually bonded. This does not change one-time
provisioning, explicit ongoing management opt-in, chat, marker deletion, or watch UI.

`CompanionBondRetentionTest` exercises transient retry versus explicit unpair,
management retention, unrelated-peer isolation, and stale/unbonded write rejection.
Paired-hardware missing-key recovery, repeated watch restart, and system unpair
acceptance remain pending. Do not install a feature APK before this change lands
in canonical `gov/main`.

Validation (2026-10-12, base `702ee4a`): offline `:app:testDebugUnitTest`
with `*Companion*`, `*PhoneManagedSettings*`, `*CompanionChat*`, and
`*CompanionPoint*` selectors passed **75 tests**, including **6 new bond-retention
tests**, with no failures. Offline `:app:assembleDebug` passed. The APK was not
installed; paired hardware acceptance is pending.

### WEARTAK-160: Single-watch phone-user map relay

The single-watch companion link relays remote user PLI in
`phone_user_cot` payloads `{cot: XML, phoneUid: nonblank}`. A
`phone_user_remove` payload `{targetUid, phoneUid}` removes only the matching
companion-owned map copy. WearOS stores this copy separately
from network CoT with the same UID, expires stale positions, clears the
companion snapshot when its BLE generation ends, and caps it at 512 users.
Only live `a-*` PLI with a TAK user identity is accepted; dropped points,
emergencies, and the watch or phone's own identity are rejected.

The Wear OS app (`:app`) uses package ID `com.aitech.weartak_civ`; the optional
Host Services SDK is a separate Android library module.

- **Android API levels:** min SDK 34, target SDK 34, compile SDK 35
- **Java:** JDK 17
- **Device:** a Wear OS watch with GPS; BLE and telephony are optional
- **Target hardware:** Samsung Galaxy Watch 7/8 and Galaxy Watch Ultra 7/8 are the intended hardware family. Samsung-specific sensor features require compatible Samsung hardware and services.
- **Native libraries:** debug APKs are built for `armeabi-v7a`.

The project includes local binary dependencies for the Samsung Health Sensor API, HAIL SDK, and WearTAK plugin SDK. Keep these files available when building locally.

Optional, backward-compatible plugin map actions and host-mediated TAK mTLS REST
are provided by the source-built [Host Services SDK 1.0.0](weartak-plugin-host-services-sdk/README.md).
Its versioned Binder extension is discovered through existing plugin initialization
extras; lifecycle APIs 1-3 and plugin admission permissions remain unchanged.

Plugin unload revokes host controls/callbacks and publishes local unloaded state
without waiting for remote lifecycle cleanup. Initialization and best-effort cleanup
run off the UI thread, with four process-wide worker slots, no work queue, and no
overlapping lifecycle generations per package. A stuck Binder call retains its
slot until it actually returns; a timeout never creates replacement blocked threads.
Binding and initialization each time out locally after 10 seconds. A package awaiting old
cleanup cannot rebind until that work returns; automatic retry remains bounded.
Plugin toggles persist the user's enabled/disabled choice across discovery refresh,
recovery and host restart. `BINDING` is distinct from initialized `LOADED`; an on
toggle indicates the requested enabled state, not a completed handshake.

## Getting started

1. Install Android Studio and an Android SDK that includes API 35, build-tools 35.0.0, and the Android NDK 27.0.12077973.
2. Configure JDK 17 for Gradle.
3. If required for your environment, create `signing.properties` with the debug/release keystore values expected by `app/build.gradle`. The build also accepts the corresponding `WEARTAK_*` environment variables.
4. Build a debug APK:

   ```bash
   ./gradlew :app:assembleDebug
   ```

5. Install the generated `armeabi-v7a` APK on a connected watch:

   ```bash
   adb install -r app/build/outputs/apk/debug/*armeabi-v7a-debug.apk
   ```

6. Launch the app:

   ```bash
   adb shell am start -n com.aitech.weartak_civ/.presentation.MainActivity
   ```

The app requests permissions at runtime. On a physical device, grant only the permissions required for the services you intend to enable (for example, location, body sensors, activity recognition, notifications, Bluetooth, and microphone access).

### Bonded Location Diagnostics (WEARTAK-165)

The **Bonded Location Diagnostics** switch is in Developer Preferences and is persisted independently of Developer Mode. Diagnostics run only after a versioned capability exchange over the authenticated, bonded, ready single-watch Companion BLE session; setup and gateway routes do not enable observation. Disabling the switch sends a session-scoped stop so both devices unregister diagnostics-only callbacks. An unsupported Companion peer receives no repeated capability probes, and a new connection negotiates a new session.

The watch observes fixes already delivered to WearTAK and registers GNSS status/raw-measurement callbacks only for an accepted diagnostics session. It does not request an additional location stream, change the authoritative PLI, or publish diagnostic data as CoT. The phone observes passive platform fixes plus GNSS callbacks; it does not take over ATAK location authority. Permission failures, disabled providers, inactive receivers, and unsupported callback data remain visible as Unavailable or Unknown rather than being treated as good. Quality states are conservative and are not a determination of jamming or spoofing.

Without an accepted session, both readouts explicitly show diagnostics inactive; this does not mean operational location or PLI is unavailable. Each new session waits for its own watch fix and phone snapshot, and displays the actual observed provider. Fused/network fixes retain their age and accuracy but are not graded using unrelated GNSS satellite measurements. Stopping the GNSS receiver does not erase an already observed fix or relabel it as satellite-derived.

The Developer Preferences diagnostics page shows watch and phone quality separately and provides diagnostic-only Entry/Exit markers, JSON export, and Clear. Local daily summaries retain no exact coordinates, device identifiers, or raw satellite identifiers; logs are bounded to 256 KiB per day and seven days of retention. Diagnostic messages are versioned, session-fenced, size-limited, rate-limited, and best-effort behind operational BLE traffic.

## Configuration and operation

The legacy `SettingsActivity` component redirects to `WearTAKSettingsActivity`.
Debug tools are a screen within settings, not a separately launchable activity.
ATOS CoT IPC authorizes the caller on every Binder transaction, before processing
the payload; service binding itself does not establish a trusted caller identity.

### Route point selection

TAK-server Data Package offers (`b-f-t-r`) can import routes from version 2
Mission Package ZIPs. WearTAK downloads the content hash from the **source server's
configured HTTPS host on port 8443**, using that server's client certificate and
certificate/hostname verification. Advertised hosts and redirects are never followed.
Non-route package content is not supported.
Offers are bound to the receiving TLS socket's current connection attempt, not
its asynchronously updated display status. Replaced/disabled/disconnected
sessions cannot borrow a new connection's credentials. A reconnect or busy importer
is reported as an operational import failure, not malformed sender XML.
Only manifest-listed, non-ignored `b-m-r` CoT entries are imported, through the
existing route parser; package commands and import/delete instructions are not executed.
Downloads are limited to 8 MiB, expanded archives to 16 MiB, individual entries to
4 MiB (manifest: 256 KiB), 256 entries, and 10,000 total route points.
SHA-256 and advertised size must match before import. Unsafe paths, DTDs/entities,
missing content and invalid routes reject the package without importing partial routes.
One package is processed at a time without blocking incoming CoT; failed imports
are logged and show a watch toast. Resend after an import failure.
When requested, transfer success/failure acknowledgements are routed back to the
sender through the source TAK server; success is sent only after route import.
Received routes with a valid CoT `stale` are stored on the watch and recovered
after process restart, including force-stop/relaunch, without a resend. Original
timestamps, source/mission ownership, method and checkpoint/control geometry are
preserved. Expired routes are not recovered; live routes also expire at their
original stale time. Legacy routes without `stale` remain memory-only rather
than being retained indefinitely. Source-scoped CoT delete watermarks survive
restart and reject older/equal replays. Local remove/remove-all and mission
removal also update the persisted catalog; restart alone cannot restore them.

Ordinary route geometry CoT (`b-m-r`) received on the configured Wi-Fi multicast
subscriptions uses the same route store, complete geometry and point actions as
server/Data Sync routes. A Mission Package ZIP is not raw route CoT over UDP:
its local `b-f-t-r` offer uses a separate source-bound peer download. Only a private
IPv4 UDP sender on the receiving Wi-Fi link, HTTPS port 8443 and `/getfile` with
exactly one numeric `file` ID and one `sender` parameter are accepted (CommonCommo
`MissionPackageManager::getLocalUrl`). The offered host must exactly match the UDP
sender. Downloads are bound to that Wi-Fi network/session with no proxy or redirects;
server credentials/client certificates are never used. ATAK serves `/getfile` with a
self-signed certificate that does not name the phone's IP, so the peer download accepts
the phone's certificate (HTTPS only, hostname must equal the UDP sender IP, no HTTP
fallback). Integrity comes from the offer's mandatory SHA-256 and size, which must match
the downloaded bytes before any route is imported. TAK server connections keep full
platform/enrolled-CA trust and hostname validation.
Peer imports share the same ZIP/hash/size bounds and route UI; identities are
scoped to the multicast source and peer IP. Peer ACK routing is not yet supported,
so a sender may report a timeout even after a verified watch import.
Live peer ZIP interoperability is not yet accepted; an observed local offer alone
does not establish successful HTTPS download or route import.
Debug multicast diagnostics record only the peer IP, offer URL scheme/port/path
host/source equality, query key names/count, and hash/size presence, not the full
offer, credentials, query values or hash values.

Tap a route line to view the whole route and select any of its points (including
unnamed vertices) for the existing point details and Bloodhound action. **Remove
from watch** requires confirmation and removes the selected route from local map
state, never the server copy. Versioned CoT copies of the same route UID are
removed together so an older transport copy cannot immediately restore the row;
legacy unversioned routes retain source ownership.
A later resend or Data Sync update can restore a locally removed route. Existing
single-point Bloodhound targets remain until stopped or removed using Bloodhound controls.

The Bloodhound picker also lists each received **whole route** once, with its total
point count and distance to the nearest vertex when a location fix is available.
Open a route and choose **Start nearest** (requires a location fix) or **Start from
beginning**. Standalone navigation now targets checkpoints (`b-m-p-w`), preserving
control vertices (`b-m-p-c`, also the default for links without a type) in route
geometry/distance. The final vertex remains the destination, including legacy
routes without typed checkpoints. Start nearest selects the next checkpoint
ahead of the closest projected position along the route, rather than the nearest
raw vertex. Arrival radii follow ATAK defaults: **Walking/Swimming 10 m, Driving
20 m, Watercraft 100 m, Flying 1000 m**; missing/unknown method defaults to Walking.
The current GNSS horizontal accuracy is added to that radius when available.
A nonterminal checkpoint must have been reached within the bubble and then
passed along the route projection before advancing. Reaching the final vertex's
arrival bubble completes the route. There are no bearing or timeout rules.
This replaces the old fixed-20-m/every-vertex behavior. The route
screen shows **Point X of N**, remaining distance along the route, the existing
Bloodhound compass, and **Stop route**. Reaching the final point shows **Route
complete** and clears the active target. Starting another Bloodhound stops route
following; removing the route from the watch also stops it. A received geometry
change stops following so an old point index cannot target a different path.
Standalone following sends no CoT/chat/acknowledgement to the sender. An active
standalone route with an unexpired persisted copy resumes its start mode,
checkpoint index and already-reached checkpoint set after restart. Range/bearing
wait for a fresh location fix. Recovery stays local and does not send a new
navigation start to ATAK on reconnection; explicitly starting a route again uses
the normal Companion handoff. Stop, completion, switching targets, removal,
expiry or changed geometry prevent recovery. Phone-owned navigation is never
auto-resumed: a fresh ready Companion snapshot remains authoritative.

Picker rows have small map, start/stop, and remove icons in a single vertical
column with separate 40 dp touch targets. Remove is local to the watch; **Remove
all** clears whole routes as well as marker/alert targets and any completed route
session. Routes can also be removed from their Bloodhound details screen. The
main-screen Bloodhound shortcut is hidden while a target is active.

Bonded, ready, single-watch Companion sessions accept `phone_route_navigation`
JSON envelopes. Version 1 payloads contain `sessionId`, monotonically increasing
`sequence`, `action` (`start`, `progress`, `stop`), and `routeUid`. Start/progress
also contain the full `b-m-r` XML in `routeCot` and a zero-based
`targetPointIndex` over every raw geometry vertex (including unnamed points).
The geometry UID must match, and malformed vertices or out-of-range indices
reject the snapshot rather than shifting its index. Stop optionally contains
`completed: true` when ATAK reports final arrival.

For phone-owned navigation, the watch follows exactly the phone's index; its
own location updates only range/bearing and remaining distance, not progression.
ATAK decides advancement and completion using its own arrival settings. A phone
completion clears the active target and retains the completed route details;
manual phone stop does not claim completion. Duplicate/older sequences and
ended sessions are ignored (bounded 128-session history). Disconnect clears the
active phone target; a fresh snapshot with a higher sequence may resume it.
Explicit watch stop, delete, Remove all, or switching targets sends a
session-scoped stop to the bonded phone and suppresses further snapshots for that
session. Removal never deletes the phone/server route geometry.

Watch-started routes on a ready bonded connection send `watch_route_navigation`
with version 1, `requestId`, proposed `sessionId`, `action: start`, `routeUid`,
`targetPointIndex`, and full `routeCot`. The phone validates/imports missing
geometry locally and starts native ATAK navigation; existing incompatible
geometry is rejected. Native snapshots use that same session ID. Watch-side
export retains raw order and marks the fallback final destination as a checkpoint
so a watch-only legacy route can be imported as navigable geometry. Watch-side
advancement pauses during takeover. `route_navigation_ack` includes `requestId`,
`sessionId`, `ok`, and optional `error`; a rejection or missing native snapshot
after **8 seconds** reports a sync failure and resumes local navigation without
an automatic retry loop. A timeout also sends a scoped stop to cancel a late
phone start. Stop uses the same message type with `action: stop`, a fresh
request ID, the session ID, and route UID. There is no competing watch progress
loop while ATAK owns navigation. Disconnect before takeover resumes local
navigation; disconnect after takeover clears the phone-owned active target.

Standalone rules are ATAK-aligned, not a reimplementation of its full engine:
the watch uses nearest-segment equirectangular projection with geodesic segment
lengths, not ATAK's spatial SQL engine. It does not share phone
`waypointBubble.<method>` preference overrides, navigation-cue-triggered
departure, engine-dirty/off-route reselection, or ATAK's navigation-loop cadence.
Bonded operation delegates these decisions to ATAK and therefore uses the native
phone's exact objective and completion events.

Route lines and named point markers remain visible on the map. Selecting a point
uses the standard marker range/bearing display, Bloodhound/In Position action,
and Back control. **Whole route** returns from point details to route removal
and the named point list. The list excludes blank names but keeps original point
indices; its count distinguishes named entries from all geometry points. Routes
without named points show an empty-state explanation; unnamed points remain
selectable on the map. Route taps have a 12 dp tolerance; individual vertex
selection is available above zoom 10, including unnamed vertices. Only nonblank
waypoint names produce map dots/labels, avoiding thousands of empty track markers
without dropping any line geometry. Hit testing runs on taps, not on each frame.
Selection and sender lookup use both the source server ID and route UID,
so matching route UIDs received from different servers cannot select each other's
points. Route parsing does not log raw CoT XML.

### Companion provisioning and optional phone management (WEARTAK-90)

The companion defaults to one-time provisioning: edit the watch callsign and server
settings, select a `.p12` certificate if needed, and tap **Send to Watch**. Wait for
the watch's successful save acknowledgement before disconnecting. Certificates and
server settings remain stored on the watch; a phone is not required afterwards.
One-time uploads do not lock watch settings or copy the phone's team and role.

**Manage watch settings from this phone** is opt-in. It copies phone identity and
resynchronizes on reconnect, with watch identity, reporting, and server edits locked.
The editing lock requires a live, successfully managed Companion session. Application-level
heartbeats run every 10 seconds; after 30 seconds without a matching response, the watch
unlocks local edits and resets the Companion transport, even if Samsung retains the shared
Bluetooth connection after ATAK exits or crashes. This detects loss of responsiveness,
not the cause of the outage. The saved management opt-in is retained, while the watch's
own identity, reporting, servers, and certificates are restored (WEARTAK-168).
A fresh successful managed-settings handshake re-applies the phone's values and restores
the lock; merely reconnecting or receiving an old heartbeat does not. Watch process/service
restart also requires a fresh handshake; if none arrives within 30 seconds, the watch
restores its own values. Older Companion builds without heartbeat support
cannot maintain the lock and must be updated alongside the watch. To return to
one-time provisioning, uncheck the option and send settings again; this releases
the lock, restores the watch's own values, and then applies the one-time upload, whose
servers and certificates stay. Unpairing also releases the lock and restores the watch's
own values.

### WEARTAK-161: Explicitly releasing phone management

Turning off **Companion App** in watch settings first clears the phone-managed
settings lock for its recorded companion peer, then disables the companion service.
Since WEARTAK-168, this stop action restores the watch's own callsign, team, role,
reporting settings, TAK servers, and certificates. A transient BLE
disconnect retains the editing lock only for the heartbeat grace period; service restart
requires a new handshake. Android unpairing also releases management and restores
the values saved before management began.
The release transaction serializes settings writes and fences managed updates for
that BLE generation, so a delayed settings push cannot relock the watch before its
release acknowledgement; management can be enabled again after reconnecting.
Settings snapshots, acknowledgements, and queued notifications retain their original
peer and connection generation. Work from an ended connection is discarded rather
than delivered to a replacement session. Management activation rechecks its session
and release fence inside the DataStore transaction, including after server refresh.
An accepted notification subscription starts a new logical companion generation:
Samsung may keep the shared physical Bluetooth link alive while ATAK closes and
reopens its GATT client. Reconnect therefore does not depend on a physical
disconnect callback to clear the previous session's management-release fence.

### WEARTAK-168: Restoring watch settings after phone management

This applies to the Companion's **Bond Single Watch** mode (managed settings). Before the
phone first overwrites the watch, the watch saves its own values for every setting the phone
writes: callsign, team (and team color), role, role category, the dynamic reporting strategy,
the constant reporting interval, and the TAK server list including `.p12` certificates and
passwords. Identity and reporting values are saved in DataStore. The server list is saved in
the same encrypted TAK server store as the live list; only a non-secret "snapshot saved" marker
is kept in DataStore. The snapshot survives a process restart, and repeated phone pushes during
management never overwrite it. Stationary, on-foot, vehicle, and alerting intervals are locked
but never written by the phone, so they are not changed.

**Configure Single Watch** (one-time upload) does not lock or snapshot anything; its servers,
certificates, and other settings stay on the watch. If a one-time upload arrives from the
bonded phone, the watch first restores its saved values and ends the bond, then applies the
upload, so the configured settings stick.

The saved values are restored without changing the system Bluetooth name, the TAK service
is asked to reconnect with the restored servers, and a PLI refresh is requested when any
of these happen:
- the phone releases management or sends a one-time (unmanaged) upload;
- **Companion App** is turned off on the watch;
- the managing phone is unpaired;
- the 30-second heartbeat lease expires, or no managed handshake arrives within
  30 seconds of a service restart.

A disconnect shorter than the lease grace keeps the phone's values, so the identity does
not switch back and forth. Releasing management, turning off Companion App, and unpairing
also clear the management opt-in. Lease expiry keeps the opt-in, so a later managed handshake
saves the watch values again and re-applies the phone's values.

Watch edits are blocked while the lock is active. If the user changes one of these settings
while management is enabled but unlocked (for example, during the restart grace), the edit
also updates the saved value, so it is not lost on restore. The same applies to TAK server
edits. Locked settings are labeled **Bonded** (previously "Phone managed").

If a managed handshake saves the servers but then fails, the saved servers are restored once
the watch is no longer locked. Installs whose bond began before identity or server snapshots
existed restore only what they saved: reporting and the current identity and servers stay.

### WEARTAK-66: Interaction-triggered PLI refresh and identity updates

Changing callsign, team, or role (but not saving the same value), opening/resuming or
recentering the map, opening own-position/distance details, sending chat, dropping/updating a point at self, starting Bloodhound
or route navigation, and activating or clearing manual/physiological alerts request a new
watch location. Actions do not wait for GPS: existing coordinates are used immediately
where available. Explicit-coordinate point drops do not move when the watch moves.
A self-point receives one same-UID location update if a qualifying fix arrives within
5 seconds; deleted points are never recreated. Map opening/recentering follows that fix
without overriding an explicit target/route camera focus. Navigation vectors follow the
normal location flow.

Identity changes publish promptly using the best available valid fix while requesting a
new one. An old fix keeps its timestamp in `weartak_location@timeMillis`; the identity
announcement itself has a current event time so servers do not discard it as expired.
Settings saved together coalesce over 150
milliseconds. PLI contains the latest persisted callsign, role and team. Without any fix,
identity PLI waits for the first usable position rather than inventing coordinates.

### Reporting and Location Input

Dynamic Reporting first uses valid real speed from the selected location input.
Below 0.44704 m/s (1 mph), it uses the saved
Stationary interval. From 0.44704 to 13.4112 m/s (30 mph), it linearly interpolates
between the saved On Foot and Vehicle intervals, rounding to the nearest whole
second (halves upward), bounded by those saved rates. At or above 13.4112 m/s it
uses Vehicle. Equal or reversed user rates remain valid and are not rewritten.
While Alerting overrides Dynamic; Constant mode and all saved rates/defaults remain unchanged.

Dynamic location acquisition uses the incumbent recognized-activity profile:
Still uses Stationary; Walking, Running and On Foot use On Foot; In Vehicle uses
Vehicle; Tilting, Bicycling and Unknown use the slower saved moving interval.
While Alerting overrides this profile. There is no unconditional 60-second idle
acquisition cap or new retry/backoff timer. Existing Wi-Fi/disconnected power
saving and dock throttling apply separately to acquisition and reporting.
Activity updates retain the incumbent confidence threshold of 50, without adding
a new expiry or hysteresis threshold. This is a requested cadence, not a guarantee that
the provider, permissions, background execution or watch power state will deliver a fix.

Activity recognition can run for Dynamic location with physiological monitoring
off. Its existing per-service opt-out and Android Activity Recognition permission
remain required. It does not enable HR, collect passive daily health metrics or
register fall monitoring while physiological monitoring is off; those retain
their own monitoring and Body Sensors permission gates.

A separate cached-data send check sleeps until the next routine reporting deadline,
with a one-second retry only when a PLI could not be sent. Shorter reporting intervals
re-arm the check immediately. Observations do not force extra PLI or timer-driven coordinate redraws.
Speed must be present, finite, nonnegative, from the current input/generation and
not future-dated. Its validity ends at that observation's expected next acquisition
deadline plus the existing six-second acquisition timeout. Missing/expired speed
remains Unknown speed: reporting falls back to the recognized-activity profile
above. Only when activity is also Unknown/unavailable does it use the slower saved
On Foot/Vehicle interval. Missing speed never implies stationary. Expiry changes
send timing only; it does not restart a pending acquisition or extend the old
observation's validity. Activity/power cadence changes keep pending native requests
and their deadlines, then use the new profile. Idle rescheduling stays anchored to
the last observation, and shortening the next deadline can only shorten existing
speed validity. Source changes still invalidate old-source requests and fixes.
Existing explicit map, self-point, alert,
identity and route-readiness refresh paths retain their own freshness/dispatch rules.
Phone-managed constant reporting and Companion-exclusive routing remain authoritative.

Routine location observations are not alarm clocks. Moving/unknown observations
use elapsed-realtime exact alarms when permitted (otherwise a logged inexact
fallback); idle observations use inexact alarms. Both may be deferred by Android
deep doze. Reprofiling to a continuous subscription and stopping location cancel
the pending alarm. SOS, alert delivery and explicit refreshes do not wait for an
alarm or a reporting deadline.

Independent trusted stationary-location and confident STILL activity evidence
must agree for 30 seconds before a 60-second minimum acquisition/reporting interval
applies. With the display off, the minimum increases to two minutes after two
additional minutes, then five minutes after five additional minutes. An affirmative
off-body observation with the display off can select five minutes without GPS or
activity evidence; unknown body state cannot. An active Bloodhound target or route,
recognized moving activity, alerts, or recent explicit interaction bypass this idle
policy. Screen-on and on-body transitions reset the idle dwell. No configured
interval longer than the idle minimum is shortened. The minimum applies to Constant
and Dynamic modes without rewriting their saved settings; fused routine requests
use Balanced accuracy while idle, while native inputs retain their selected provider.
Before slowing PLI, the existing interval bridge announces the corresponding stale
window (two effective intervals plus 15 seconds, subject to the existing bonded-watch
stale policy). During deep doze a deferred observation can outlive that window;
expiry is preferable to claiming that a sleeping watch supplied a fresh fix.

Companion mode does not blindly disable watch location: the legacy PLI envelope,
self marker, local Bloodhound and route navigation still consume watch fixes.
Physiological `self_status` itself needs no GPS, but its negotiation alone does
not prove that all positional consumers have handed ownership to the phone.
The same bounded idle cadence therefore applies while paired, rather than
removing the location safety net or changing the Companion protocol.

Screen-off passive-SA deferral reduces local projection work, not incoming
network bytes. TAK channel selection can already reduce server fan-in, but
automatically changing channels or installing a subscription filter could hide
alerts, chat, navigation targets or other operational data. No automatic channel
changes, new server filter commands or Companion receive drops are introduced here.

When Dynamic reporting has a trusted stationary fix (reported horizontal accuracy
at most 25 m), WearTAK arms the low-power, one-shot significant-motion sensor when
available. A trigger requests **one fresh fix**, without changing either cadence.
The first usable post-request fix confirms movement only with horizontal accuracy
at most 25 m and either reliable speed or accuracy-aware displacement:
speed must be at least `max(1.0 m/s, 0.44704 m/s + 2 * speedAccuracy)` with reported
speed accuracy, or distance from the stationary anchor must exceed
`max(15 m, 2 * (anchorAccuracy + fixAccuracy))`. Both position accuracies must be
at most 25 m. These are initial engineering thresholds for later controlled
evaluation, not measured watch noise limits or statistical guarantees.

A wrist gesture, desk bump, noisy/poor fix or six-second timeout leaves the saved
stationary cadence and existing observation deadline intact, and re-arms the
trigger. No second fix, automatic retry, provider fallback or moving-rate tail is
added. Separate false triggers can each incur one bounded acquisition. Motion
verification shares an already-pending high-accuracy interaction request when
possible, without extending either deadline. Cached/pre-request/future and stale
source-generation fixes cannot confirm motion. While this stationary gate is
active, activity-only changes and speed expiry cannot silently switch to a moving
cadence; raw activity remains available to health/CEP consumers.

Confirmed movement releases the gate, using the saved moving rates and existing
power/dock transforms. Displacement-only evidence never invents speed and overrides
old zero-speed/STILL state until the next routine observation or its expected
deadline plus six seconds. Routine observations continue throughout and can resolve
movement independently. The stationary anchor is not moved by failed verification
fixes. Without a significant-motion sensor, or if arming/re-arming fails, the gate
is removed and the incumbent speed/activity behavior above remains in effect.
Hardware can miss triggers and slow/indoor departure may not confirm on one fix;
the existing scheduled observation is the safety net, not a guaranteed rapid
departure detector. Constant/phone-managed rates, SOS/manual alerts, physiological
alert refreshes and explicit interaction dispatch retain their existing semantics.
No continuous accelerometer sampling or new location permission is added.

The speed thresholds follow the [pinned public ATAK implementation](https://github.com/deptofdefense/AndroidTacticalAssaultKit-CIV/blob/889eee292c43d3d2eafdd1f2fbf378ad5cd89ecc/atak/ATAK/app/src/main/java/com/atakmap/comms/ReportingRate.java);
WearTAK retains its own user rates and power policy, uses clamped interpolation
without ATAK's normalization offset, and adds no distance, heading, altitude or
speed-change immediate-send triggers.

The former Reporting Strategy page is now **Reporting**. The main location icon
opens it. Every top-slot metric readout, including MGRS and Lat/Long and active
alerts, opens **Top Display Metric**, keeping all metric choices and Physiological
Monitoring/Alerts reachable. Changing Location Input does not change the saved
top-display metric.

Top Display puts a navigation-only **Environmental Alerts** button before
**Physiological Alerts**, then **Physiological Monitoring**.
All full labels fit on one line with intrinsic-safe responsive text sizing.
The alerts label still opens threshold settings independently of its switch.
Environmental alert categories are ordered Atmospheric Pressure, then Immersion;
physiological entries are ordered Exertion, Heart Rate, then Physiological Alerts.
Alerting Preferences itself sorts Battery, Environmental, then Physiological Alerts.
All three menus sort their visible localized labels. Environmental rules retain their independent switches;
the top-display button opens their existing configuration without changing settings
or adding an environmental master switch.

Home coordinates retain their complete precision on one line. Their centered
readout is inset from the round display edge and uses the existing intrinsic-safe
text fitter between 10sp and 13sp, respecting the system font scale. The coordinate
top slot gets additional vertical space for the heading and readout; physiological
metrics keep their existing layout. No coordinate is abbreviated or ellipsized.

**Location Input** offers **Fused Location**, then **Internal GNSS**. Fused Location
uses Google Play services' real fused location client, combining available satellite,
Wi-Fi and cellular sources; it is not Android's network-only provider. Internal GNSS
uses only Android's `GPS_PROVIDER`. The selected input applies to periodic, scheduled
one-shot and interaction/current-PLI acquisition. Missing services/providers, disabled
location, missing permission and failed fresh-fix acquisition are reported explicitly;
neither selection silently falls back to another location input.

An absent saved selection defaults to Fused Location without persisting an implicit
choice. Explicit saved GNSS and fused selections survive upgrades/restarts. An explicit
legacy `network` choice remains network-only, shown as **Network Only (Legacy)** until
the user chooses one of the two current options; it is never silently relabeled fused.
Unknown persisted selections remain unavailable, rather than defaulting silently.
Historical samples are not relabeled or reused. Changing the input clears current/best-known acquisition
caches and rejects callbacks and pending PLI snapshots from the previous input, including
switch-away/back races. A fresh actual sample is required; no synthetic `0,0` is created.
GPS requires fine location permission; fused and legacy network acquisition accept
coarse or fine, subject to Android's resulting precision.
Phone-managed Reporting remains locked, and location opt-out remains authoritative.
Existing reporting intervals and accuracy/freshness/privacy policies are unchanged.
Scheduled one-shot acquisition is now explicitly cancelled after its existing six-second
wake-lock window; interaction acquisition remains bounded to five seconds.

The separately enabled, existing BLE-beacon refinement remains a post-acquisition
feature, not a fallback provider. When it adjusts a fix, outgoing coordinate provenance
is `BLE`, not falsely `GPS` or `NETWORK`; acquisition-provider metadata remains distinct.

Overlapping interactions share a selected-provider acquisition bounded to 5 seconds. The fix
must be usable and measured after the latest interaction; cached coordinates are not
re-stamped as a fresh fix. PLI uses persisted identity and is sent as soon as a qualifying
fix arrives, not literally instantly. Missing permission, provider failure, or no fix ends
the refresh without sending an extra PLI. This does not change periodic reporting,
reconnect behavior, or enable services/transports that are disabled.

Enabling or changing an outbound connection requests a new current self PLI once the
route is actually usable, without waiting for the reporting interval. Successful Ditto
SDK startup, each connected TAK server, SITX, a multicast destination, and opted-in bound
plugins have independent readiness identities. Unchanged status does not repeat the
announcement; reconnects do. Changes within 150 milliseconds share one fresh-fix request.
The fix must follow the readiness change and be at most five seconds old. A timed-out
acquisition leaves the announcement pending for a later valid fix, unless its routes
are disabled or removed. Location opt-out and revoked permissions suppress it.

This path sends only current self PLI, with persisted identity and privacy/freshness-gated
physiology; it does not replay chat, alerts, commands, marker queues, or history.
Ditto receives its native `track` document, preserving the actual fix time in `b`/`n`.
Connected Companion remains exclusive: direct routes, including Ditto, receive no copy.
An ordinary location-triggered PLI can satisfy the pending announcement without an
additional PLI from that trigger. Existing periodic policy and independently queued
transport work otherwise remain unchanged.

Manual and physiological emergencies send immediately with the newest available valid
fix, even when it is older than the former 3-second cutoff. The last valid fix is retained
in memory against invalid/out-of-order callbacks. The original fix time is exposed as
`detail/weartak_location@timeMillis` (Unix milliseconds) and remains unchanged in the
Companion snapshot. For a fix older than 3 seconds, CE grows by at least 5 metres per
second (or the recorded speed if greater), capped at the unknown-error sentinel
9,999,999 metres; missing/invalid accuracy is unknown, never zero. This is a conservative
movement estimate, not a guarantee that an old fix is current.

If there has been no valid fix, no positional emergency is generated. Instead an immediate
GeoChat says `EMERGENCY from <callsign>: location unavailable, acquiring GPS`; the
positional emergency is queued until a real fix arrives. Clearing before that fix removes
the queued activation and sends a cancellation chat rather than replaying a cancelled
alert later. GeoChat's standard non-positional point placeholder is not an emergency
map location. SMS behavior remains independent.

Each alert activation and each cancellation is delivered once per route. Later location
fixes do not resend an active alert (receivers such as TAK Server bridges treat every copy
as a new notification); the user's position keeps updating through PLI. A bounded
in-memory receipt cache (1,024 uid/state/route entries) suppresses repeated submissions to
each configured route, including Companion/Gateway, while still sending the positional
alert once GPS arrives after a no-fix GeoChat, a cancellation, or a route that had not yet
accepted the alert. Receipts record local transport acceptance, not
remote-server acknowledgement. Independent routes can still converge on the same
downstream server; no watch-side dedupe can identify that topology. There is no special
repeated-send loop for 911.

Configure the TAK endpoint and identity/certificates in the app before attempting to connect. Service enablement is persisted in `DataRepository` and centrally evaluated by `ReactiveServiceManager` and `ServiceConfiguration`; a service will run only when its setting, permissions, and prerequisite conditions are satisfied.

The app starts essential behavior through its application/bootstrap receivers and coordinates most long-running work as foreground services. This is intentional: direct TAK connectivity, location tracking, sensor collection, and optional integrations are service-driven rather than tied to a single UI screen.

Chat is an online feature; the project does not provide an offline TAK chat queue.

Companion chat uses the existing watch inbox. Replayed message IDs are deduplicated
per sender, including duplicates within a batch. Messages without IDs use sender,
receipt timestamp, and text rather than timestamp alone.

### WEARTAK-118: Existing group conversations

Standalone TAK chat uses `__chat.id`/`chatroom` and the `chatgrp.uidN` member list:
named groups containing the watch merge all senders into one room, and replies keep
the room ID/title while targeting the most recently received member UIDs with
`marti/dest uid`. Groups without known reply recipients are rejected and logged,
never converted to a generic broadcast. Membership is learned from received chat,
not a group-management service; create/update groups in ATAK and include the watch's
device UID. Other private conversations and own-sender echoes are filtered out.
`All Chat Rooms` is a single explicit broadcast conversation, not a sender DM.

With a matching companion plugin, `chat_received` retains `conversationUid`,
`conversationTitle`, `isGroup`, and `participantUids`. Replies carry canonical `roomUid`,
`roomTitle`, `isGroup`, `msg`, and `cs`, plus legacy `rmUid`/`rmTitle` aliases.
Direct replies additionally carry `recipientUid`/`replyTo` with the same target UID;
group/broadcast replies omit these direct-only fields. The phone accepts `text` as
an alias of `msg`, rejects conflicting aliases, and resolves the existing ATAK group and its current
recipients. Missing/unwritable groups and empty recipient sets fail with phone logs,
without a broadcast fallback. Legacy envelopes without an explicit group flag remain
sender DMs. Existing direct chat and the explicit All Chat Rooms action remain supported.
Inbox navigation follows room UID rather than a changing list index.

The chat picker has two entry actions above the existing conversations:
**All Chat Rooms** opens the canonical broadcast room, including before any message
has arrived. **Select User for Chat** opens the map: tap a person and confirm **Chat with this
person** to create/open a private conversation in the original picker activity and
add it to the normal chat-room list. Cancel/back
creates no room and sends nothing. Selection temporarily shows the people layer
regardless of saved team/role filters without changing those preferences; markers
and alerts are not private-chat recipients. Live UID lookup prevents list reordering
from changing the selected person, and removed/self/invalid contacts cannot be selected.
Chat must be enabled and running; an unavailable service is reported.
Standalone initiated private chats route by recipient UID, not callsign. Companion
private sends resolve a real ATAK IndividualContact with a GeoChat endpoint and use
ATAK's native targeted chat API, preserving native phone conversation history.
Unknown/self/broadcast-like direct contacts are rejected, never broadcast.
Picker order is All Chat Rooms pinned first, Select User for Chat second, then
direct/named-group rooms by their latest message activity descending, including
local sends. Messages inside each conversation also appear newest first, with
date headers and previews using the same message timestamps. CoT event time or
Companion `sentAt` determines chronology when available; legacy messages without
that timestamp use receipt time. Older timestamped replays do not promote a room.
Equal timestamps use deterministic sender/message and room UID ordering.
Receipt timestamps remain separate for unread tracking. Stable message keys
preserve the reading position when newer messages arrive while scrolling.
Empty rooms follow rooms with messages; canned preset ordering is unchanged.
The broadcast room appears only in its pinned entry, not again in the sorted list.
BLE uses version-1 `msgType: chat_received` inbound and `msgType: chat` outbound,
with a `payload` object and message ID in `msgUid`/`payload.uid`. Inbound retains
`senderUid`, `senderCallsign`, `msg`, and `receivedAt` (epoch milliseconds).
`isGroup` absent/false stays direct-compatible; no group-owner field is needed.
The Android WearOS BLE path does not advertise Garmin `relay_status.chatGroups`:
only a verified group-aware Garmin transport adapter may announce that capability
for its matching session. BLE enqueue remains unacknowledged chat delivery.

This supports existing groups and initiating direct/broadcast chats, not named watch group creation, private-messenger support,
or historical ATAK chat synchronization. The companion relays incoming remote ATAK
messages while connected; phone-authored outgoing messages and phone-attributed watch
echoes are not mirrored back. BLE enqueue is not an end-to-end delivery acknowledgement.

Hardware acceptance (not yet performed): use two remote ATAK senders and one outsider.
In standalone mode include the watch UID in an existing named group, send from both
members, verify one room with both callsigns, and reply by preset and keyboard; only
members should receive the reply. Repeat with the paired phone UID in companion mode
and both updated builds. In each mode test direct chat alongside the group, All Chat
Rooms, replay/duplicate delivery, and a new room arriving while a group detail/reply
screen is open. Check room/read state and the reply destination. Remove/empty the ATAK
group before a companion reply and verify the phone logs a rejection with no unrelated
delivery. Disconnect/reconnect BLE and confirm existing chats resume without promising
offline history. Smoke-test paired-local point deletion to confirm WEARTAK-112 behavior.
In both modes, start from an empty inbox: open All Chat Rooms and send by keyboard
and preset, checking native send-to-all behavior. Tap Select User for Chat, cancel first, then
select one person (also with the saved people layer disabled), confirm, and send.
Only that person must receive the private chat; on companion also check ATAK's direct
conversation history. Reorder incoming contacts while the person detail is open,
then remove the selected contact and check that confirmation becomes unavailable.
Confirm existing room history/read state survives reopening either entry action.

Outgoing periodic and interaction-triggered PLI expires after twice the active reporting
interval plus 15 seconds (135 seconds for a 60-second interval). Interaction PLI retains
the location fix timestamp as the expiry baseline. Alert and marker expiry is unchanged.

The map's Channels button is greyed out and cannot open or change channels while
admin network settings are locked or a live Companion session manages settings.
Lock changes take effect immediately, closing any open channel selector. Unlocking
restores normal channel selection.

Channel HTTPS requests automatically discover a legacy TAK server's TLS name when the configured address does not match its certificate. For example, the watch can keep connecting to `takserver.nctak.org` while validating the certificate against its exact DNS SAN, `takserver2.nctak.org`. Discovery requires a certificate trusted by the imported/enrollment CA that is not also trusted by the device's public roots, and exactly one supported, non-wildcard DNS SAN. Normal hostname validation (including the existing exact Common Name fallback for certificates without SANs) remains unchanged. The discovered identity is reused for channel requests until the TAK connection is reset; a different identity is not silently substituted during that connection. Discovery failures appear in channel errors and diagnostic logs.

This fallback does not change the CoT stream or enrollment connection. A Data Sync API client is not currently implemented in this app.

### TAK certificate enrollment

Username/password enrollment uses HTTPS port **8446** (`tls/config`, then
`tls/signClient/v2`) with normal platform certificate and hostname validation.
Configuration failures stop enrollment before CSR generation/signing. Signing
responses may be JSON or XML with a required `signedCert`, OpenTAKServer's optional
unnumbered `ca`, and optional numbered `caN` certificates (Base64 DER or PEM).
Authorities are retained as trusted entries; chains are leaf-first, then `ca`,
then numbered CAs in numeric order. Malformed nonblank CA values are rejected.
Registration errors identify the failed stage and distinguish HTTP 401
authentication rejection, other HTTP statuses (including 403), TLS, connectivity,
and invalid certificate data. Retry clears the previous error for that server.
Add/edit registration waits for the saved configuration before starting an attempt.
The dialog follows a stable server-ID request across runtime configuration refreshes;
failures replace the spinner, and retry starts an attempt even when settings are unchanged.
Offline, disabled, companion-managed, cancelled, or stopped-service attempts also
terminate registration rather than leaving it loading; a completed TLS handshake
publishes success. Restarting the TAK foreground service creates a fresh connection
scope and retires the prior service's work. Late teardown of an old instance cannot
cancel the replacement's connections.
This does **not** repair rejected credentials: HTTP 401 still requires valid
credentials/server-side authorization. Response bodies and certificate material
are not logged by enrollment.

Transient initial socket and enrollment transport I/O failures retry automatically
after 5, 10, 20, 40, 80, then at most 120 seconds between attempts. Each server
owns one cancellable, generation-fenced pending retry; success resets the failure
backoff. Disabled servers, companion-managed connections, duplicate endpoints,
network unavailability and service shutdown retain their existing gates.
The UI reports a failed/disconnected connection while waiting, not a successful
or actively connecting session. HTTP rejection, authentication, TLS/trust and
certificate errors require user action rather than automatic retries. Network
failures do not delete valid cached certificates or force re-enrollment. Recovery
uses delayed jobs, not a polling loop; existing established-session drop backoff
is unchanged.

### Network transport checklist

Network Preferences starts with native enable switches sorted by the current
locale's visible labels, including eligible installed, bound connectivity plugins.
Checked means saved **enabled intent**, not a connected session; changes apply
immediately, with no Apply button. Existing individual configuration pages remain
below. The native rows are Ditto Edge Sync, Sit(x) TAK, TAK SA Multicast, TAK Servers
and WearTAK Companion. No unfunded goTenna, Somewear or Everywhere placeholder,
HTTP integration, SMS alert or Data Sync feature is presented as a transport.

TAK Servers shows live connected/configured totals (for example, **3/5 Servers**).
Its master switch defaults on. Turning it off cancels all server connection
generations, sockets, readers, pending retries and associated enrollment/channel/
mission/package HTTP calls, without erasing per-server enable selections,
credentials or certificates. The TAK foreground service, multicast and shared
coroutine scope stay alive. Turning it on resumes eligible servers under existing
network and companion-exclusive routing gates.

TAK server edits and provisioning reloads reconcile serially against saved server
IDs, not delayed UI indexes. Unchanged servers retain their in-flight connections;
removed or replaced runtimes are fenced and eligible replacements reconnect.
Enable notifications use saved intent (they never invert it), and can retry a
failed connection. Initial route collection also replaces pre-collector attempts.
TLS handshakes have a 15-second read deadline; connected CoT readers remain blocking.
Sideloaded PKCS12 passwords stay in runtime state rather than changing the saved
configuration used to decide whether a connection needs replacing.
While charging or docked, off-wrist TAK connections stay continuous under the
existing server, network and Companion gates. Plugging in reconnects immediately
and cancels pending off-body window closes. Unplugging while off wrist resumes
30-second connection windows at inexact 180-second intervals; on-wrist behavior
is unchanged. Power changes reuse the process-wide battery observer.
During an eligible off-wrist batching gap, enabled TAK server rows and details
and the TAK status in Network Preferences show **Off-Wrist**, matching the main
display. Connection windows retain normal connected/connecting status; charging,
docking, disabled servers and inactive transport gates never show the gap label.
Connection failures take precedence and remain visible when a batch window closes.

CEP simulation is disabled in release and ordinary debug builds: it is excluded
from managed service registration/startup, its Android component is disabled, and
direct entry points cannot create notification 8123. Explicit debug test builds
with `-PCEP_SIMULATION=true` retain simulation startup and notification behavior.

TAK keepalive waits for its next receive-silence, ping-repeat or unanswered-response
deadline, re-evaluating on inbound bytes instead of polling every five seconds.
The existing silence configuration (zero disables; positive values have a 15-second
floor), ping/reconnect policy and connection-generation fencing are unchanged.

Sit(x) shows the selected group and actual connection state. Ditto shows only
**Community**, **Custom PDL** or **None**; the same safe subtitle is used in Plugins.
The PDL itself is never displayed as a subtitle. Network/phone-management locks are
rechecked by setting writers, not only by the visible switches. Trusted Android
managed configuration remains authoritative and can update the locked transport
settings atomically.

External rows require an installed, bound plugin whose validated settings schema
declares the functional Boolean `forwardOutboundCot` field. Disabled forwarding
still appears and can be enabled; stale/unbound plugin callbacks are rejected.
An unloaded plugin has no trusted live schema and is not guessed from its name.
A future implemented Somewear plugin appears automatically when it meets that
contract; the unfunded built-in placeholder does not.

With existing opt-in Logging enabled, `CONNECTIVITY_PATH` records numeric state
changes, an initial snapshot, five-minute snapshots and charging/unplug-boundary
snapshots. `KIND` identifies TAK=0, Ditto=1, Sit(x)=2, Multicast=3, Companion=4,
and aggregate bound connectivity plugins=5. `ENABLED` records intent (an enabled
plugin count for the aggregate); `ACTIVE` records actual sync/route availability,
not evidence of payload traffic or peer presence. TAK records `CONNECTED_COUNT`
and `CONFIGURED_COUNT`. Ditto `MODE` is None=0, Community=1, Custom PDL=2.
Sit(x) records only `GROUP_SELECTED`, not the group name. Multicast `REQUESTED`
comes from the actual local Wi-Fi request, not the preference alone;
`IDLE_RELEASED` marks the ten-minute inactivity release. `LOCK_HELD` is now always
zero because multicast no longer takes a high-performance Wi-Fi power lock.
Plugin records contain counts,
not package names. No hosts, credentials, raw PDLs, group names or peer identifiers
enter these records. Logging off cancels snapshot subscriptions and timers.
These additions do not retroactively add evidence to earlier or ongoing trials.

Multicast only requests local Wi-Fi when enabled, the companion is disconnected
and the configured address resolves to a valid IPv4 multicast group with a valid
UDP port, matching receiver eligibility. Its request is released after ten minutes
without a non-self multicast packet. The receiver remains passive whenever Wi-Fi
is otherwise connected. Re-enabling multicast, changing its address/port, or a
companion routing change re-arms demand; ordinary network callbacks do not, and
there is no polling or periodic reacquisition. Incoming peer packets extend an
existing demand window but do not reacquire an idle-released request. The receiver's
separate multicast filtering lock is retained; the removed high-performance power
lock is not needed for packet reception. No normal-connected battery saving is
claimed without a controlled measurement.

### Optional Samsung HRV acquisition

Samsung `HEART_RATE_CONTINUOUS` acquisition in `SkinTempService` is now **off by
default**, including its HRV-only screen-off flush loop. The separate platform
`HeartrateService` remains the heart-rate source. Skin temperature, SpO2, PPG and
CRM acquisition are unchanged.

An explicit build flag, `-PenableSamsungHrv=true`, re-enables the Samsung
continuous HRV path for a controlled comparison; normal builds omit that property
and use `false`. Invalid values fail the build. This is not a new watch setting,
and changing the flag requires a separately built, verified artifact.

Opted-in HRV emits only a new valid RMSSD sample, with its original sample time,
while running and worn and no older than the existing 120-second HRV freshness
limit. Stop/disconnect/off-wrist clears its cache and flush callbacks; it does not
clear the handler queue shared by other sensors. Late or empty batches cannot
refresh the cached RMSSD. Missing numeric CEP inputs remain absent, rather than
falling through to legacy Boolean false/zero. An absent HRV contributes no HRV
points to the cold score and cannot satisfy the HRV-drop or low-HRV recalibration
corroborators; the other cold-score inputs still work.

Under opt-in Logging, `SAMSUNG_HR_CONTINUOUS` records `ENABLED` (build opt-in)
and `ACTIVE` (SDK listener attached). It records the current state at logging/
process start and actual attach/unset/disconnect transitions, not HR values or
health identifiers. This enables a later attributable A/B; it is not evidence of
independent Samsung Health waste or a measured battery saving.

### Persistent developer menus and Logging

Tap the version eight times in **Preferences** to unlock **Admin Preferences**
and **Developer Preferences**. Repeat the same eight-tap gesture to hide them.
This local UI preference now survives navigation, process restart, reboot and
in-place updates until explicitly disabled. It is not an authenticated admin
session: phone-managed restrictions, network locking, licensing, platform
permissions and Knox authorization remain independent and unchanged. Hiding
the menus immediately removes access to their pages, not those restrictions.
The unlock gesture no longer changes airplane mode. **Logging** and **Stay awake**
are simple toggle rows at the top, alphabetized within that group, without helper text.
**While charging** follows **Stay awake** directly in every locale.
The remaining developer entries are sorted by their current localized visible labels,
with the title and Back navigation kept outside the sorted entries.

Open **Developer Preferences** and switch **Logging** on
before a normal unplugged session. This is one app-wide, default-off recording
preference, not a battery-only switch. Its choice survives app/watch restarts and
updates. Recording resumes when Android next runs WearTAK; it does not launch
the app or override force-stop, suspension or battery restrictions. Hiding the
developer menus does not turn off Logging or erase evidence. Re-enable
the menus and explicitly switch **Logging** off to stop it. **Logs**
retains the export and clear actions; **Clear logs**
deletes only this recorder's files, without changing the preference; switching
off alone preserves recorded evidence. Uninstall/app-data clearing removes it.
Existing Bonded Location Diagnostics remains a separate, unchanged feature;
the Logging switch does not enable its GNSS measurement callbacks. Only the
user-facing name changed from Diagnostics; saved consent, default-off state,
journal contents, storage paths and technical schema identifiers are unchanged.

Ditto diagnostics use this same **Logging** switch, JSONL retention and **Logs**
export/clear path, with no separate files or consent:
- `DITTO_LIFECYCLE`: `KIND` is start=0, identity validation=1, initialize=2,
  start sync=3, power policy=4, transport reconfiguration=5, subscription refresh=6,
  companion handoff=7, stop sync=8, diagnostic registration/cleanup=9.
  `RESULT=1` marks entry/success, `0` a failure or suppressed startup.
- `DITTO_STATE`: current `ACTIVE` sync, power `SUPPRESSED`, and identity
  reconfiguration `REQUESTED` flags at opt-in/process start and state changes.
- `DITTO_TRANSPORTS`: configured transport bitmask (`TRANSPORT`):
  BLE=1, LAN=2, Wi-Fi Aware=4, outbound WebSocket=8. Recorded on configuration
  changes and when diagnostics attach to a running Ditto peer.
- `DITTO_PEERS`: remote peer count (`CONNECTED_COUNT`, including indirect peers),
  direct local connection count (`SOCKET_COUNT`, not Android TCP socket count),
  and observed connection-type bitmask (`TRANSPORT`): Bluetooth=1, AccessPoint=2,
  SDK P2PWiFi=4, WebSocket=8. Initial presence and changed numeric projections
  are captured; connection IDs, roles, peer metadata and names are not retained.
- `DITTO_SDK_LOG`: SDK callback occurrence counts by severity (`KIND`):
  error=0, warning=1, info=2, debug=3, verbose=4. Ditto 4.14.6 exposes a
  process-global custom log callback. WearTAK discards its message argument
  without parsing or persisting it; PDLs, shared keys, license tokens, payloads
  and exception text never enter these records. SDK verbosity/enabled settings
  remain unchanged, so counts include only callbacks the SDK actually emits,
  not every internal SDK event. Raw SDK file logging/export is not enabled.

The SDK hook and passive presence observer exist only while Logging is enabled
and DittoService owns its peer; disabling Logging or stopping the service releases
them. Logging does not start sync, alter transports, discover peers, or request
network access. Counts/configuration are best-effort snapshots, not proof of
delivery, sync completion, hardware scanning or Wi-Fi Direct support. These events
share the existing five-second grouping, bounded queue and overflow reporting;
rapid state changes can be grouped, with retained metadata flushed on export/off.

The journal contains only compiled event categories and allowlisted numeric
metadata: app/activity lifecycle, best-effort Java crash occurrence, battery
percentage/charging/dock changes, location requests/acceptance/rejection/timeouts/
coalescing, observation schedules versus report-policy changes, saved reporting/
source/monitoring configuration and usable-versus-Unknown speed status, PLI dispatch
attempts/results, power policies, existing location/Stay awake lock hooks,
service start/stop requests, HR listener registration, connectivity transitions,
sync/chat/command occurrence and command success/failure. Service requests are
not proof that Android started a service; a successful PLI result means an
existing transport accepted the dispatch, not a delivery acknowledgement.
This is troubleshooting evidence, not a promise to capture every app error.

Connection troubleshooting also uses existing callbacks, without additional
network subscriptions, polling, uploads or changes to retry/connectivity policy:

- `NETWORK_LIFECYCLE`: `KIND` is snapshot=0, available=1, capabilities=2,
  lost=3, unavailable=4. `ACTIVE` describes presence for that callback/snapshot,
  not proof of Internet reachability. `VALIDATED` and `INTERNET` are capability
  flags; `TRANSPORT` is a bitmask: Wi-Fi=1, cellular=2, Bluetooth=4,
  Ethernet=8, VPN=16. Available/lost callbacks have no capability snapshot;
  their zero flags mean unavailable metadata, not failed validation.
- `TAK_CONNECTION`: `KIND` is request=0, attempt-start=1, TLS-start=2,
  success=3, failure=4, invalidated=5, retry-scheduled=6, retry-started=7,
  skipped=8, reader-ended=9, write-failed=10, keepalive-timeout=11,
  status-snapshot=12. `RESULT` is a fixed reason: none/unspecified=0,
  companion=1, service-stopped=2, terminated=3, disabled=4, no-network=5,
  duplicate-endpoint=6, active-attempt=7, attempt-limit=8,
  stale-generation=9, authentication=10, HTTP=11, TLS=12, connectivity=13,
  certificate=14, end-of-stream=15, cancelled=16, reader-error=17,
  socket-write=18, keepalive=19, reset=20. Unclassified status-only failures
  retain reason 0 rather than guessing a cause. `GENERATION`, `ATTEMPT`,
  `DELAY_MS`, `STATUS` (existing runtime status 0-5) and `ACTIVE`
  (existing network flag) are numeric. Generations are local cancellation
  fences, not globally unique connection or server identifiers.
- `TAK_CONNECTION_COUNTS` samples enabled connected/connecting status counts,
  adopted open/connected socket count and the app's aggregate
  `UI_CONNECTED`/`UI_CONNECTING` flags at lifecycle transitions. These are
  best-effort concurrent snapshots, not atomic delivery or liveness proof.
  They help distinguish an empty displayed connection count from runtime
  sockets; they do not probe a socket or perform a network request.

These records contain no server IDs, addresses, hostnames, network handles,
session UUIDs, credentials, payloads or exception text. They share the existing
five-second repeated-key grouping (latest numeric metadata plus `COUNT`),
256-key ceiling, bounded writer queue and journal retention. Rapid transitions
can be grouped; export/disable flushes retained metadata. Logging off skips
snapshot construction, and disabling/clearing keeps the existing consent epochs.

Received-marker ingestion preserves every source-owned copy, event-time/receipt/
insertion tie-break, removal watermark and per-update state publication.
An accepted single-source upsert compares only that UID against its cached visible
winner rather than rebuilding winners for unrelated UIDs; removals and bulk
transforms still rebuild the full projection. The all-source snapshot remains
published on every accepted change. DEBUG-only map/navigation marker diagnostics
defer source lookup, channel projection and string formatting until DEBUG is
enabled; warnings, stale handling, expiry, guidance and opt-in Logging are
unchanged. Deterministic equivalence/work tests establish less projection work,
not a measured watch CPU or battery saving.

New records identify the current hybrid as `hybrid-motion-confirm-v3`; older
records keep their original policy, app version and source commit.
`REPORT_POLICY` captures the initial/enabled snapshot and subsequent changes in
selected intervals, activity or selection reason, not every speed tick. Its
`KIND` is 0 for Constant or 1 for Dynamic; `REPORT_SECONDS` and
`OBSERVATION_SECONDS` are the **desired** intervals, not measured send/fix gaps.
`ACTIVITY` uses the native enum codes: running 0, walking 1, tilting 2, still 3,
bicycling 4, in-vehicle 5, on-foot 6, Unknown 7.
`REPORT_REASON` and `OBSERVATION_REASON` use explicit codes: Constant 0, alert 1,
stationary speed 2, interpolated speed 3, vehicle speed 4, recognized activity
profile 5, Unknown/unsupported activity fallback 6, motion-held stationary 7 and
motion-confirmed moving 8. Valid fresh speed owns
Dynamic reporting; activity owns acquisition and the invalid-speed reporting
fallback. Tilting, bicycling and Unknown use the existing conservative maximum
of foot/vehicle intervals, not the stationary interval.
Correlate these selections with `POWER_CHANGED` power/dock flags and applied
report interval, `OBSERVATION_SCHEDULED`/`LOCATION_REQUEST` applied acquisition
intervals, fix outcomes and `PLI_ATTEMPT`/`PLI_RESULT`. Neither desired intervals
nor a local accepted dispatch establish server delivery or an acknowledgement.
This metadata does not acquire a fix, send a message or change a cadence.
Recognized walking/running and the generic activity policy are not third-party
workout start/end evidence. No Health Services user-activity subscription, health
permission or exercise session is added; user-reported external workout times
remain separate annotations when no existing callback exposes them.

There are no raw coordinates, speed/health readings, identity/contact/chat
contents, command names/arguments, exception messages/stacks, credentials,
tokens, server addresses or device identifiers in this journal. No logcat
interception, uploads, network listener, wireless-debugging enablement, extra
GPS/sensor/wakelock/network requests or battery polling is added. Existing
app callbacks and opt-in passive SDK callbacks feed the recorder. Events are
limited to one record per category/
source/kind/state per five seconds; repeated occurrences are aggregated as counts,
including on export/off. The queue is bounded to 128 records, the aggregation
table to 256 keys, and overflow is explicitly counted. An I/O failure pauses
file writes and is visible in Android logging and the Logs page; retry
export or clear after fixing storage. Process termination can lose queued
events and unflushed aggregate counts.

Storage is three rotating JSONL files, each at most 2 MiB (6 MiB total),
with up-to-three-day retention enforced on write/export (a file is discarded
when its oldest record expires). Capacity is exercised against a synthetic
12-hour, 60-second observation trace with request/fix/timeout/lock/report events;
busier sessions can still rotate away earlier evidence, so retention is not a
guaranteed recording duration. This capacity does not change event frequency or
acquisition behavior. Records include schema,
app version/code/source commit, reporting-policy version, wall-clock milliseconds
and monotonic elapsed milliseconds. Elapsed time restarts on watch reboot;
do not subtract elapsed timestamps across boots. `COUNT` includes aggregated
occurrences; `AGGREGATED=1` denotes a summary, not another occurrence.
`LOCATION_ACCEPTED.DURATION_MS` is time since request/subscription start, not
per-fix latency on a continuous subscription. Numeric source/kind values follow
the version's `LocationInput`, acquisition and `ServiceType` enums; screen codes
are other=0, map=1, settings=2, chat=3, main=4. Battery dock codes are none=0,
USB=1, wireless=2; sensor kind 1 is the HR listener; lock kind 1 is Stay awake
(the location lock has no kind field). PLI result is accepted=1, rejected=0,
exception=-1; connectivity result uses `TakServerConnStatusEvent` constants.
Location acquisition kind codes are periodic=0, one-shot=1, interaction=2,
motion-confirmation=3. `MOTION_CONFIRMATION.RESULT` is armed=0, triggered=1,
confirmed=2, timeout=3, acquisition-failed=4, sensor-absent=5, arming-failed=6,
not-confirmed=7; `ACTIVE` indicates the shared stationary latch. Location motion
verification uses lock kind 2, with the same bounded timeout/duration semantics.
Configuration source -1 means no selected input; an omitted battery percentage
means it has not yet been observed.
Speed validity result is usable=1, Unknown=0, without recording the speed.
Location lock `ACTIVE=1` carries its configured timeout; `ACTIVE=0` carries
elapsed time capped at that timeout (an upper bound, not measured energy).
Its release result is explicitly-held=1 or already-released/expired=0.

After a session, connect using your existing authorized wireless ADB workflow
(these commands do not pair, enable debugging or change watch settings).
For the signed production package:

```powershell
adb pull /sdcard/Android/data/com.aitech.weartak_civ/files/diagnostics .\wear-tak-diagnostics
```

Keep all available `events.jsonl`, `events.1.jsonl`, and `events.2.jsonl`;
oldest is `.2`, newest is unsuffixed. Read only complete JSON lines if Android
terminated a write. Debug builds use package `com.aitech.weartak_civ.dev`.
Scoped-storage access depends on the watch OS/ADB policy. If direct pull is
denied, **Export logs** uses Android's document picker to save a bounded JSON
snapshot to a user-selected destination, then retrieve that file with ADB.
The page reports export/storage failures and unavailable document pickers;
no broad storage permission or exported provider is introduced.

For corroboration, collect existing OS evidence without resetting state:

```powershell
adb shell dumpsys batterystats com.aitech.weartak_civ > .\wear-tak-batterystats.txt
adb logcat -b crash -d > .\wear-tak-crash.txt
```

These manual OS outputs are separate from the privacy-allowlisted app journal
and may contain sensitive or other-app information: inspect/redact before
sharing. Native crashes, system kills and tombstones may not reach the Java
handler or app file. Do not run `batterystats --reset` or clear logcat for this
workflow. Charging, wireless debugging, a connected debugger and **Stay awake**
can materially alter power use, connectivity and dock throttling. Capture the
normal unplugged session first, and note the later debugging/charging interval.
For a practical battery comparison, hold hardware, build, screen use, sensors,
transport/RF conditions, saved rates and dock policy constant; compare several
matched unplugged sessions with elapsed time, battery percentage drop and OS
wakelock/radio/GPS evidence. Event counts cannot establish per-component energy
use or prove battery savings, and no physical battery measurements are claimed.

### Power context in developer Logging

Power context extends the same **default-OFF**, local-only developer journal.
Schema stays **1**; additive `POWER_CONTEXT`, `DEVICE_SETTING` and
`RESOURCE_LIFETIME` events carry `CONTEXT_VERSION=1`. Consumers must accept these
new enum names. No new permission, permission prompt, polling loop, alarm,
sensor acquisition, network request or wake lock is introduced. Exactly one of
the six existing five-minute connectivity path ticks captures global context.
Other captures use existing battery callbacks (before battery percentage
de-duplication), charging boundaries, screen/radio/power broadcasts, display
callbacks and fixed Settings URI observers. Logging off unregisters context
observers/listeners/receivers and cancels pending context callbacks. Clear
invalidates pending writes/callbacks and resets the clock epoch.

`POWER_CONTEXT` records adjacent elapsed/uptime clocks at observation time:
`CAPTURE_ELAPSED_MS`, `UPTIME_MS`, `PREVIOUS_ELAPSED_MS`,
`ELAPSED_DELTA_MS`, `UPTIME_DELTA_MS`, `ASLEEP_MS` and
`ASLEEP_BASIS_POINTS` (divide by 100 for percent asleep).
Elapsed time includes system suspend; uptime excludes it. This estimates
**system suspend**, not human sleep, Doze residency, process CPU time or energy.
For example, elapsed +300,000 ms and uptime +30,000 ms is 90% asleep.
`CLOCK_STATUS` is baseline/unavailable=0, valid=1, invalid=2.
The first capture after opt-in, clear or process restart has no interval.
Clock rollback, zero-length intervals, negative uptime deltas and inconsistency
larger than 5 ms are invalid, with no fabricated percent. A clock pair is
bracketed by elapsed reads (`CLOCK_SKEW_MS`) and retried once if skew exceeds
5 ms; only <=5 ms negative sleep discrepancies are clamped.
`NEVER_SUSPENDED` is measurable suspend=0, exact zero sleep=1,
indistinguishable from zero within 5 ms=2. `SHORT_INTERVAL=1` marks intervals
under one second. Raw deltas remain available. Only an admitted snapshot
advances the baseline. Queue/storage loss can remove endpoints; exported rows
are not necessarily a continuous interval chain.

`REASON` is initial=0, existing five-minute tick=1, state/battery callback=2,
charging boundary=3. Event-driven full snapshots are limited to one per
30 seconds except initial/charging boundaries. `IDLE`, `LIGHT_IDLE`,
`INTERACTIVE`, `DISPLAY_STATE` (Android Display state codes) and `SAVER`
are independent observations; -1 is unavailable. Screen-off does not prove
suspend or AOD. The AOD setting and a dozing display are separate evidence.
`CURRENT_UA` is signed instantaneous battery current in microamps
(Android convention: positive charging, negative discharging);
`CHARGE_UAH` is charge counter in microamp-hours, not integrated interval energy.
`CURRENT_STATUS` and `CHARGE_STATUS` distinguish real zero from unavailable
properties; unsupported `Long.MIN_VALUE` is never exported as a value.

`DEVICE_SETTING` records changes, not arbitrary names or strings. `READ_STATUS`
decodes as readable=0, **unreadable (missing)=1**, **unreadable (denied)=2**,
**unreadable (malformed)=3**, unsupported=4. Omitted `VALUE` is not false/zero.
Namespaces: System=0, Secure=1, Global=2, effective platform state=3.
`OBSERVER_AVAILABLE` reports successful URI registration; numeric
`OBSERVATION_FAILURES` counts unavailable observation registrations.
Observers are registered before the initial read; pending notifications
reconcile changes. Denied observers fall back to existing callbacks/snapshots.
Notifications timestamp observation, not the actual transition; changes while
suspended or between callbacks can be missed. No complete transition coverage
is claimed.

The fixed setting IDs are:

| ID | Namespace | Raw setting / effective state |
| --- | --- | --- |
| 0-8 | Secure | `wake_gesture_enabled`, `doze_pick_up_gesture`, `doze_pulse_on_pick_up`, `doze_tap_gesture`, `doze_double_tap_gesture`, `wake_on_gesture`, `wrist_gestures_enabled`, `doze_always_on`, `ambient_enabled`, respectively |
| 9/10 | System/Secure | `lift_to_wake` |
| 11/12 | System/Secure | `double_tap_to_wake_up` |
| 13/14 | System/Secure | `aod_mode` |
| 15/16 | System/Secure | `aod_tap_to_show_mode` |
| 17 | Global | `stay_on_while_plugged_in` (plug-type bitmask, not Boolean) |
| 18 | Global | `low_power` |
| 19-21 | Platform | Bluetooth adapter state, NFC enabled, Wi-Fi radio state, respectively |
| 22-26 | Platform | Device idle, light idle, interactive, display state, effective power saver, respectively |

Gesture/Wear/Samsung keys are compatibility probes, **not verified effective
toggle semantics** on a particular watch; `SEMANTICS_VERIFIED=0` preserves that
distinction. Missing keys are unreadable, never guessed disabled. No hidden API,
settings enumeration/write, peer names, MACs, SSIDs, scans or bonded-device
enumeration is used. Bluetooth permission denial is unreadable without a new
permission prompt. Effective state transitions are retained independently of
the full-snapshot cadence gate.

New context detail shares an opportunistically refilled **60 records/minute**
budget with burst **16** (two slots reserved for full power snapshots), bypassing
the old five-second repeated-key grouping. Churn becomes fixed-identity
`AGGREGATED=1` summaries with latest state, `SUPPRESSED`, `FIRST_MS`, `LAST_MS`;
these are explicitly lossy, not ordered transitions or additional acquisitions.
Summaries flush on existing snapshots/export/off, with no timer/hardware read.
The allowlist has 19 raw Settings probes, eight effective-state probes and
at most 32 resource classes; inventory is capped at 64 active tokens.
Each new serialized record is capped at **2 KiB**. The existing 128-record queue,
256-key aggregation limit, three 2 MiB files, three-day lazy expiry and visible
storage failure handling remain. Capacity can rotate in under an hour at
sustained churn: retention is a cap, not a guaranteed recording duration.

Resource records describe **WearTAK-owned** acquisitions/registrations, not
sensor/health values. They distinguish CPU, screen, Wi-Fi/multicast locks,
sensors/trackers and connectivity/subscription requests. Logging can be enabled
mid-hold without changing operational resources; disable never releases them.
Recorder-local monotonic `TOKEN`s, counts, elapsed start/duration/timeout,
requested sampling period and per-resource union duration allow overlap-safe
analysis; don't sum overlapping holds as CPU-awake percentages. Registration
failure is not active, removal requests are not confirmed cleanup, and timed
expiry discovered at the next existing `isHeld` observation has uncertain actual
release time. No timer is added to discover expiry.

Resource ID / class codes are fixed:

| Resource ID | Resource | Class |
| --- | --- | --- |
| 1 | Location CPU lock | 1 (CPU lock) |
| 2 | Debug CPU lock | 1 |
| 3 | Debug screen lock | 2 (screen lock) |
| 4 | Debug Wi-Fi lock | 3 (Wi-Fi performance lock) |
| 5 | Multicast filtering lock | 4 (multicast lock, not CPU) |
| 6-7 | Debug / multicast connectivity request | 5 (connectivity request, not CPU) |
| 8-14 | Heading, HR, off-body, pressure, environment pressure/light/humidity | 6 (Android listener) |
| 15-18 | Samsung skin, PPG, SpO2, continuous HR | 7 (Samsung tracker) |
| 19 | Activity recognition | 8 (activity subscription) |
| 20 | Health Services passive listener | 9 (passive subscription) |
| 21-22 | Native / fused location | 10 (location subscription) |

`ACTION`: begin=1, end=2, already-active=3, failed=4, uncertain=5,
removal-requested=6, local-callback-disabled=7, expiry-observed=8,
inventory-overflow=9, summary=10, stale-callback=11.
`RESULT=0` accompanies failed/uncertain/expiry/overflow/stale actions and
previously uncertain holds/summaries; otherwise it is 1, **not a hardware-awake
Boolean**. `ACTIVE` includes uncertain outstanding registrations, but excludes
overflowed tokens. `UNION_MS` in summaries is cumulative for the accounting epoch:
**do not sum successive summaries**. Resource-summary slots are separate from
suppressed detail, so a latest detail cannot erase cumulative unions.
Clear silently resets accounting while retaining operational holds and tokens;
the next boundary or existing snapshot reconciles them without refilling the
just-cleared journal during an immediate export.

**Other apps' wakelocks and kernel wake sources (for example `ssp_wake_lock`)
are NOT visible in-app and still need adb batterystats.** Lack of suspend does
not identify its cause. Authorized later OS collection complements the journal;
kernel detail depends on build/privileges and may require other OS traces.
WearTAK does not read restricted proc/sysfs files, execute adb, request privileged
battery permissions or attribute an awake interval to another app/OEM.
Screen/clock timing can reveal behavior despite numeric-only privacy rules:
explicit opt-in, local export/clear and bounded retention still apply.

### Dev Mode Stay Awake

Debug and release APKs include opt-in **Stay awake** and **While charging** toggle rows in
**Developer Preferences** (tap the
version eight times in Preferences to reveal these tools). This standalone demo/development
manual session control does not require Wireless debugging or read its system setting. Starting
requires an enabled Wi-Fi radio and known external power. Otherwise a brief toast
explains the refusal and the manual switch stays off. Charging-only mode
additionally requires a running WearTAK service manager.
The switch holds screen, CPU and Wi-Fi locks and keeps a Wi-Fi network actively requested, including
while viewing system settings. The request does not require Internet access, so local-only
hotspots can satisfy it. It shows an ongoing notification with a Stop action. It remains
enabled until you turn off the switch, tap notification Stop, switch Wi-Fi off,
or unplug the watch.
The switches reflect their independent saved choices.
Wi-Fi radio `DISABLING` or `DISABLED` broadcasts stop the session; ordinary AP
disconnects, roaming, or loss of Internet validation do not. Power-disconnected
and battery plugged-state broadcasts stop both modes without polling.
Automatic shutoff releases the active network request and all locks immediately,
unregisters its service receivers and removes its notification. Wi-Fi shutoff
persists manual mode disabled; the charging opt-in remains saved. Charging-only mode
restarts on a later power-connected edge or WearTAK startup with known external power.
Leaving the UI or swiping the recent task does not stop the foreground service.
There is no separate update-persistence option: enabled users retain their choice
regardless of the old checkbox, subject to Wi-Fi and power availability. Explicitly disabled
choices stay disabled. The charging opt-in is also retained across updates/restarts,
but does not start the full WearTAK service stack at boot.
The service requests `START_STICKY` recovery after ordinary process death; the
package-update and post-unlock boot receiver restore only this service when enabled.
Opening WearTAK retries a previously blocked restore. Missing or unknown Wi-Fi/power
prerequisites turn the manual choice off and explain the refusal with a toast.
Unknown power state fails closed for charging-only mode without erasing its opt-in.
Other Android startup failures release partial locks and report an error without
turning the saved choice off. These hooks never
launch the main activity or enable the full WearTAK/Companion BLE service stack.
Updates and restarts temporarily release locks. Android can defer or deny service
recovery (including force-stop, system task-manager stop, battery policy or permission
restrictions), so recovery is best-effort, not uninterrupted operation. Boot restoration
is not direct-boot/lock-screen bypass. Saved intent survives reboot/power cycle, not
uninstall, app-data clearing or factory reset; no screen can stay awake while powered off.
There is no timed expiry. The notification warns **Extra battery use. May heat watch.**
The Wi-Fi request is released on stop, service destruction, or startup failure.
The active request intentionally creates Wear OS Wi-Fi demand for ADB, but the
radio-disabling edge releases that demand before persisting shutoff; it never
re-requests a network after that edge. It does not call APIs to force Wi-Fi on
or guarantee connectivity against RF conditions, hotspot
disconnects, or system overrides. It does not prevent an explicit screen-off action or
guarantee ADB/multicast delivery. The switch
is only available in the hidden Dev Mode menu.

With Logging enabled, AppDiagnostics records `STAY_AWAKE_AUTO_DISABLED` and
`STAY_AWAKE_START_REFUSED`, with numeric `REASON` codes: `WIFI_OFF=1`,
`UNPLUGGED=2`, `WIFI_UNAVAILABLE=3` (radio not yet enabled or state unknown),
and `POWER_UNKNOWN=4` (no usable sticky plugged state). These events retain
individual reasons rather than coalescing rapid transitions.

### TAK SA multicast

With TAK SA Multicast enabled and the watch directly connected to Wi-Fi, WearTAK sends and
receives position/other CoT on the configured multicast endpoint (default `239.2.3.1:6969`)
and GeoChat on `224.10.10.1:17012`. It also receives direct ATAK points/other CoT on
`224.10.10.1:6969`, since ATAK uses that port for non-chat traffic to the advertised UDP contact.
Incoming traffic uses the same contact, marker, and chat
handling as TAK-server traffic; receiving chat requires the chat service to be enabled.
Multicast status indicates that the sender and all receivers are ready, not that a peer
has been discovered. The Wi-Fi access point must permit multicast between clients.
Multicast tracks local Wi-Fi independently of Android's default internet connection,
so cellular routing changes do not stop receivers on a connected radio Wi-Fi network.
When multicast has an eligible configured endpoint and no companion route, WearTAK
requests local Wi-Fi for a bounded demand window without a high-performance power
lock or internet requirement. Ten minutes without non-self packets releases demand;
changing multicast configuration, re-enabling it, or a companion routing change
re-arms the window. Otherwise reception is passive on Wi-Fi that remains available.
The request is also released when multicast is disabled or the TAK connection service
stops. This uses the existing multicast toggle and does not keep the screen or CPU
awake. It does not pin an SSID/BSSID, prevent roaming,
override OS power restrictions, or guarantee screen-off delivery. Multicast sockets remain
bound to the Wi-Fi network and are recreated when that network changes.
The active demand window can remain in place with the screen off; this is not an OS
power-policy bypass. Connection loss, validation/suspension capability changes and
interface changes are logged without including SSIDs, credentials or IP addresses.
Reception supports both XML CoT and binary TAK Protocol v1 datagrams, including GeoChat
and ATAK contacts using friendly equipment/vehicle position types.
Multicast position broadcasts advertise the GeoChat UDP endpoint so ATAK can send direct
messages to the watch without a TAK server; server-bound broadcasts retain their server endpoint.
The receiver ignores datagrams sent from this device's local addresses, including markers
whose event UID differs from the watch UID. Binary decoding preserves custom XML details
such as `t-x-meshcfg`/`__meshtastic_cfg`; a plugin declaring `configShareUrl` can receive that
configuration through its validated settings interface.

### Plugin CoT

Plugins can opt in through their settings schema and enabled boolean values:
`forwardOutboundCot` receives watch-generated CoT as `cot.outbound`, while
`receiveInboundCot` receives incoming CoT as `cot.inbound`. Both actions carry `xml`;
incoming actions also carry `source` and `receivedAt`. Incoming delivery covers direct
TAK, multicast, SITX, and companion CoT IPC without changing their existing processing.
Plugin-injected CoT is processed locally but is not sent back into plugin bearers,
preventing reinjection loops. Unsupported CoT types can still be observed by opted-in plugins.

These subscriptions do not start or enable any network transport, and observing inbound
CoT does not retransmit it. A BLE-only configuration remains BLE-only. The companion BLE
protocol itself is unchanged; it does not currently provide a raw incoming CoT feed.
Plugin `requestCotDispatch` retains its existing TAK-server relay by default; a plugin
can declare `relayInboundToNetwork` and set it false for local-only injection. That relay
does not enable disconnected TAK servers or fan out to other transports.

## Architecture

### Paired Bloodhound tracking

While the ATAK companion is bonded and BLE-ready, starting Bloodhound tracking on
either device starts the same CoT target on the other. Ending that shared session on
either device stops tracking on both, without cancelling the underlying emergency,
dismissing its alert, or deleting its marker. Closing a screen is not a tracking stop.
The watch must have its Bloodhound service enabled and running; companion commands
do not override disabled services or missing permissions.

Commands use `bloodhound_control` envelopes with an `action`, `session_id`, and
`target_uid`. Starts carry the target callsign and coordinates, allowing the watch to
create a companion-owned navigation target if the UID is not already available.
Existing locally received targets are preferred. Start/stop acknowledgements use
`bloodhound_control_result`; rejection or a missing acknowledgement is surfaced rather
than claiming synchronization succeeded. Session correlation, bounded ended-session
history keyed by session and target UID, and echo suppression prevent old commands
from stopping a replacement track. A repeated stop is acknowledged only for its
original session/target pair.
Simultaneous starts converge on the lexicographically smaller session ID.
An `update` action carries changed target coordinates within the same session, so a
companion-only target can move without restarting tracking. Updates do not overwrite
an independently received local CoT target; a real incoming source can replace the
companion snapshot without ending its tracking session.

Disconnect leaves each device's local tracking unchanged and forgets the shared session.
Reconnect does not replay an old start or stop; a new tracking action is required to
synchronize again. This does not depend on the separate phone-to-watch alert forwarding
feature and does not retransmit alert cancellations.
The Bloodhound picker has a **Remove All** button above its targets. Its confirmation
screen offers **Remove All** and **Cancel**; Cancel or dismissing the dialog leaves
the list unchanged. Remove All removes all
current source copies of ordinary targets and locally dismisses remote emergency alerts,
stopping navigation to removed targets. Alert dismissals survive restarts and suppress
ongoing refreshes until the sender cancels. This does not send cancellation or acknowledgement
messages, delete ordinary map markers, or prevent new ordinary targets from arriving.

### Phone-to-watch points

Paired companion BLE accepts `phone_marker_cot` envelopes with original marker CoT
in `payload.cot`. Valid points appear on the map and in Bloodhound under
`Companion ATAK`, without a separate watch TAK connection. Blank UIDs, invalid
timestamps, non-finite coordinates and out-of-range latitude/longitude are rejected.
Original event/start/stale times are retained; missing start or stale defaults to
event time, as with existing marker ingestion.

BLE, direct TAK and SITX share serialized map/navigation ingestion. Source-owned
copies are deduplicated by UID using event time before receipt time. A stale
incoming marker removes only its source's copy at or before that event time,
including navigation to that removed copy; another source's target remains intact.
Source-scoped removal watermarks reject older and equal-time replay during the
watch process lifetime, including BLE reconnects. Disconnecting does not clear
received points. There is no offline replay queue or new automatic expiry policy.
Asynchronous repository failures are logged; cancellation is not treated as an error.

### Position reports before the first fix

Periodic position PLI is withheld until the repository has a valid timestamped,
finite latitude/longitude fix. A later invalid/missing callback reuses the newest
valid last-known fix with its original fix timestamp and age-inflated uncertainty,
never a fabricated `0,0` position. This applies to TAK, Sit(x), multicast, plugin,
Companion gateway/legacy BLE, and the independent Ditto `track` publisher.
The CoT/legacy Companion builders also reject no-fix PLI rather than creating a
zero-coordinate point. A real timestamped fix at `0,0` remains a valid position.

### Camera identity in Bloodhound

The Bloodhound picker shows a camera icon for an exact `b-i-v` CoT event or
an otherwise supported entity with a direct `event/detail/__video` element.
This is presence-only classification: names, URL strings, generic sensors and
SPI points alone do not imply a camera. Marker/user projections retain this
identity through existing TAK, raw companion and Ditto ingress paths and into
the existing detail-to-Bloodhound action. Names, distances, selection and
navigation controls are unchanged. No media URL is loaded or validated, and
no snapshot, stream or historical SRT/KLV ingestion is added by this icon.

### MOPP and Data Sync plugins

**MOPP** is an installed plugin (`com.weartak.plugin.mopp`), not a built-in
settings page. Its normal Plugins entry opens the plugin-owned selector:
**None**, **MOPP 0** through **MOPP 4**. An unsaved selection is null, distinct
from an explicitly saved None or level 0. The installed row shows the authoritative
saved status as **None** or compact **MOPP0** through **MOPP4**, collected
lifecycle-aware without writes; the manifest subtitle remains the loading fallback.
Publishing is always on. Tapping a level saves immediately through the typed
host Binder, without a separate Save button.

WearTAK remains authoritative for the existing `mopp_status`, `mopp_saved_at`
preferences. The legacy `enable_mopp` flag cannot disable effective publishing:
typed state always reports enabled, saves work even with a persisted false flag,
and legacy toggle writes retain revision/retry semantics but normalize it to true.
Reads require no migration writes and never invent a selection or timestamp.
Atomic revision checks and mutation IDs prevent
stale writes and duplicate retries from changing saved state or timestamps.
Missing, disabled, disconnected or incompatible plugins stop MOPP contributions
to CoT remarks, Ditto and Companion self-status without deleting the saved
selection or original timestamp. Reporting resumes only
after a compatible plugin completes initialization/start and an authoritative
read; reconnecting never creates a new save timestamp. No plugin means no
editable MOPP entry. This does not detect PPE/CBRN, assess protection/compliance,
set a commanded level or start a filter timer.

The bundled `weartak-plugin-sdk-release.aar` is derived from canonical SDK
`https://git.tak.gov/weartak-plugins/weartak-plugin-sdk.git` at immutable merged
commit `e2ee52402cf5738e86e43c3f6f1ce1594bd6e5ec` (tree
`19874cd3b54bb080b037a7392aa1bd15f760daa6`). Release SHA-256:
`bd69781da7b6efe2aa3da1617953815a55b225b47eb5c2fa6f2c17ae055b4363`.
This is a source-built dependency, not an independently trusted binary release.

**Data Sync** now has its own icon and split enable/navigation row. The default
remains on. Off pauses new mission API requests and background polling without
unsubscribing or deleting content/progress; On resumes on the current direct
TAK session. A request already in flight may finish. Mission settings remain
viewable while paused, but subscription actions are disabled. Other TAK traffic
and bonded-companion transport gating are unchanged.

Mission discovery uses `GET /Marti/api/missions?defaultRole=true`, matching
TAK's default-role listing for the current authenticated certificate. Named
mission paths and subscription query encoding are unchanged; this does not
change server permissions.

### Companion saved PPE and fresh physiology (SELF_STATUS v1)

The watch sends status only after the current bonded BLE READY connection
receives `{"msg_type":"self_status_capability","version":1}`. Older phones
receive unchanged legacy PLI. Once a status snapshot is queued, legacy JSON
PLI omits its untimed `hr`; the negotiated snapshots carry timed physiology
instead. This does not create an independent reporter or change standalone
CoT routing, the phone's UID/location, or operator remarks.

Every notification is a full snapshot, for example:

```json
{
  "vers": 1,
  "msgType": "self_status",
  "msgUid": "watch-uid",
  "src": "wearos",
  "payload": {
    "version": 1,
    "session": "a1b2c3d4-1234-4567-89ab-123456789abc",
    "seq": 0,
    "deviceUid": "watch-uid",
    "model": "WEAROS",
    "ppe": {"level": "MOPP 2", "savedAt": null},
    "physio": {
      "hr": 72,
      "measuredAt": "2026-10-24T18:15:30Z",
      "sktF": 93.4,
      "sktFMeasuredAt": "2026-10-24T18:15:20Z",
      "exert": 0.094,
      "exertMeasuredAt": "2026-10-24T18:15:30Z"
    }
  }
}
```

- `session` is a new UUID per connection generation; repeated capability
  messages retain it. `seq` starts at zero and increases on successful queue
  admission. Old-generation queued frames cannot reach a replacement connection.
- `ppe` is null for an unsaved status or unavailable MOPP plugin. Saved levels are
  exactly `None`, `MOPP 0` through `MOPP 4`; candidates never enter the sender.
  The required `savedAt` key is ISO-8601 UTC for a new Save, or null for an
  existing saved choice with unknown save time. None remains distinct from level 0.
- `physio` is null without an eligible metric. Otherwise omit absent metric
  keys: whole-number `hr` 20..250 with `measuredAt`; Fahrenheit `sktF` 59..113
  with `sktFMeasuredAt`; fraction `exert` 0..1 with `exertMeasuredAt`.
  Global physiological monitoring, individual HR/skin switches and worn state
  gate publication. HR uses the sensor's monotonic sample time converted to UTC;
  skin uses the Samsung data point's timestamp. Exertion reuses the existing
  HR-reserve calculation from real HR, saved age and resting HR, retaining that
  HR sample's time. No snapshot/receipt/keepalive timestamp substitutes for a sample.
- Each metric expires independently after 120 seconds and is rejected more
  than 30 seconds in the future. Snapshots are checked every second: new samples
  are rate-limited to one snapshot per 15 seconds, saved PPE/policy changes and
  metric removals bypass that limit, and a keepalive is due every 55 seconds
  while READY, leaving scheduling margin below the 60-second limit (queue
  failures are logged and retried).

The matching Companion receiver owns the phone self-PLI merge and cleanup. It
binds the first accepted session/device UID to READY, requires increasing sequence
numbers, and removes watch contributions on disconnect/unbond or 300 seconds
without a snapshot. It merges a provider `<device>` into existing
`<biometrics version="1">` using `model` WEAROS, watch `uid`, newest `sampleTime`,
and individually timed `<hr>`, `<skt>` (Fahrenheit for WEAROS), `<exert>` children.
The JSON skin key remains `sktF`, not `skt`. Managed remarks use
`MOPP2; HR: 72 bpm @ 18:15:30Z; Skin: 93.4F @ 18:15:20Z; Exert: 9% @ 18:15:30Z`;
None is omitted, and other remarks/providers remain intact. A matching phone
release is required; synthetic coverage is not physical BLE/ATAK acceptance.

### Top display metrics and shortcuts

Tap the top metric to open a simple scrollable list: **Exertion, Heart Rate,
Lat/Long, MGRS, Skin Temp, Steps**, sorted by the localized displayed labels.
Picker rows show the name and live value/status together on one line, alongside
the selection indicator. Lat/Long uses `latitude/longitude` on one line.
Picker and all home metric values use intrinsic-safe native text auto-sizing to
fit the available width and height, including enlarged system fonts, without
removing signs, grid fields, or precision (five decimal places and 1 m MGRS).
The home chip reserves compact vertical padding for the value instead of
clipping heart-rate glyphs inside its equal-height dashboard row. Fitting does
not use `BoxWithConstraints`/subcomposition inside intrinsic-measuring chips.
Underlying and transmitted location values are unchanged.
Existing Steps and retained selections persist; removed selector choices
(CRM, calories, floors, SpO2/tier) resolve to Exertion. This only limits the top
selector, not those features or collectors (except calories, removed entirely).
The existing CRM Reserve screen
remains accessible from **Plugins > CRM** alongside its threshold settings.

Calories are no longer requested from Health Services, cached in daily metrics,
or transmitted. Passive monitoring still requests supported Steps, Floors, and
Elevation Gain daily data types with the same accumulation behavior. The unused
daily-health entity field and all localized calorie labels have been removed;
there is no active Room database/table requiring a migration. There are no
calorie exercise requests, CEP rules, plugin SDK/AIDL fields, or active calorie
tiles/complications/settings to retain or deprecate. The plugin SDK archive and
its binary contracts are unchanged.

Legacy stored `key_calories` top-metric selections still resolve to Exertion;
obsolete calorie preference keys are ignored. Profile preset JSON continues to
ignore obsolete/unknown top-level fields, including calorie fields, while
importing its supported profile, servers, units, and alerts normally.
Companion PLI no longer contains `cals`; ESRI and Tablet Command biometrics no
longer contain `calories`, including the disabled-metrics `-1` placeholder.
Receivers must tolerate these absent optional fields rather than requiring a
numeric value. Steps, floors, elevation, and native TAK CoT detail are unchanged.

The list starts with **Physiological Monitoring**, then **Physiological Alerts**,
using matching native toggle chips before the metric choices. Merely opening it
preserves both switches and all thresholds. The Alerts chip's text area opens
the existing thresholds even while alerts are off; its separate switch never
navigates, and opening thresholds never toggles it. Existing alert-threshold
editors and managed-settings behavior are reused.

The home MGRS readout is text-only, with its full one-meter value retained; active
alert warning icons, Lat/Long and the reporting shortcut are unchanged. Heart Rate
uses the same recognizable filled-heart vector as the physiology dashboard.

When monitoring is off, Exertion, Heart Rate, Skin Temp **and Steps** show
**Disabled**, ahead of old readings or wear state. This Steps display rule does
not stop an independently running step collector. With monitoring enabled,
wear-dependent values show **Off-Wrist** only after an actual off-body sensor
observation. Startup, missing-sensor and restarted-sensor uncertainty shows
`---`, not an inferred off-wrist state. Lat/Long and MGRS remain
location-driven regardless of monitoring. Missing/invalid/disabled location
shows **No fix**, never invented coordinates; a real timestamped `0,0` is valid.
Lat/Long uses five decimal places; MGRS reuses the map's one-meter formatter.

Heart-rate listener registration is owned by the service lifecycle and follows
Monitoring, the individual heart-rate setting, body-sensor permission and the
repository's observed worn state. Repeated starts/complication wakes do not
duplicate or stop registration, and removing the activity task does not stop
these manager-owned sensors. Refused registration and returned permission are
reconciled every five seconds only while collection is allowed; missing heart
rate is not treated as off-wrist and does not trigger a synthetic reading or
silence-based restart. Destroyed and pre-registration callbacks are rejected.

The off-body on-change sensor is different from a biometric sample: Android may
deliver its current activation's retained wear observation when a new listener
registers. That real binary observation is accepted even if its original
timestamp predates this listener's registration. Impossible timestamps and
duplicate/older observations are rejected, so an older worn callback cannot
overwrite a later off-wrist observation. No callback still means unknown, not
worn; the stricter heart-rate sample freshness gate is unchanged.

The home location-status icon opens existing reporting-rate settings without
toggling location. Stay awake retains its label and real on/off control without
row helper text; its automatic shutoff behavior is described above.

### Core observation lifetimes

Weather runs only when the authoritative effective CEP configuration has a
weather dependency, a nonblank OpenWeather key is built in, and the existing
location-service dependency is available. All executing feature definitions count,
including nested custom operators: the built-in wind-chill feature uses
`ambient.temp_c` and `ambient.wind_mps`, and wetness uses `water_sensor.wet`.
Rule conditions, render/message/snapshot keys and exit monitors also count.
Managed/plugin composition installs and swaps update demand; rejected candidates
do not. Removing only a rule does not stop weather if an executing feature still
needs it. Absence is never replaced with zero. An inactive-to-active configuration
transition does not inherit old weather ingestion state.

Missing-key direct starts stop rather than retaining an idle foreground service.
Weather stop cancels its HTTP call and clears the cached snapshot; failures wait
for the existing backoff interval instead of spinning. Weather logs never include
the key, request URL, coordinates, response body or exception details/chains.
The existing successful-fetch time/distance gates are unchanged.

Battery percentage, dock type and charging state are observed once per process,
with an initial sticky sample and deduplicated updates, not by a separate battery
foreground service. PLI, Ditto, plugins, UI/system snapshots, diagnostics and power
cadence need these values even when battery alerts are disabled. `BATTERY` remains
an observable virtual capability in service status. This observer does not itself
keep a killed process alive; existing foreground owners retain their longevity.
Legacy battery notification cleanup is retained for upgrades.

Off-body observation remains active for its live CEP, physiology and self-status
consumers; unknown is not off-wrist. Movement retains its dormant service host
and existing rotation demand (active target, or interactive heading subscribers/
complications), without changing background navigation. Power policy keeps its
network/dock/settings events and foreground host, but schedules its one-time
five-second startup in its existing cancellable coroutine scope rather than an
otherwise idle dedicated thread. These lifecycle changes are not a measured
physical energy-saving claim.

Verbose service-state table generation is debug-only; normal service state
publication remains available in release. Stay awake remains an intentional
user-enabled feature: boot/package replacement restores it only with saved
enabled manual intent and valid Wi-Fi/power prerequisites, never on a fresh install or
after explicit or automatic manual off. The separate charging opt-in only runs
with WearTAK's service manager and known external power.

### WEARTAK-130: chat commands

Tool Preferences > Plugins > **Commands** (off by default) lets another TAK user change watch settings by sending chat commands. **Allowed Callsigns** is a case-insensitive list shown alphabetically; add one callsign at a time, tap an entry to edit it, or use its right-side trash icon to remove it. Existing comma-separated allow lists remain supported. All commands accept direct messages, All Chat Rooms broadcasts, and groups containing the watch UID. Only messages from allowed callsigns are acted on. Messages from other senders, stale messages (older than 5 minutes) and replays are dropped silently.

TAK servers, Wi-Fi multicast, Ditto GeoChat, SIT(x), and the paired phone's raw-CoT
gateway and legacy `chat_received` bridge share one command runtime. Replies use
only the originating transport and conversation, not normal chat fan-out. A server
is not required for multicast commands. After a reset,
first open WearTAK, grant its required permissions and connect watch and sender to
the same multicast-capable Wi-Fi network. Enable **Network Preferences > TAK SA
Multicast** (with valid address/port), then **Plugins > Commands** and add the
sender's callsign to **Allowed Callsigns**. Keep Chat enabled for the normal inbox
and outcome replies. This is bootstrap configuration, not a full backup restore.
Enabling multicast also enables normal multicast publishing, not a receive-only
provisioning mode; use an appropriately scoped test network.

The receiver listens on the configured group/port plus standard GeoChat
`224.10.10.1:17012` and direct CoT `224.10.10.1:6969`. In the sender, choose the
actual watch contact for a **direct** chat, a group containing the watch UID, or
All Chat Rooms to provision multiple watches. Direct CoT must
identify the watch as a recipient: normally `__chat.id`/`chatgrp.id` is the watch UID
and `chatgrp.uid1` is that UID (`uid0` is the sender); `remarks.to` can name the watch
UID/current callsign. Watch UID membership also supports direct chats without
`remarks.to`. Missing or other-device recipients are not a provisioning shortcut.
Keep the device clocks within five minutes. Phone-managed identity/network locks
still apply. A factory reset may change the UID: discover the current contact
rather than replaying a saved recipient UID.

The shared process-local replay cache ignores repeat sender/message-ID pairs
across every ingress route, including either arrival order. It remembers the
last 128 accepted pairs, not a durable execution ledger across app restarts.
Consumed commands are not inserted as ordinary chat or rebroadcast; one normal
outcome reply is generated for a newly accepted command. With Commands disabled,
addressed command-looking text retains normal chat behavior, except `usep12`,
`setpdl`, and `enrollserver` are always consumed to keep credentials out of the local inbox.
The legacy phone bridge must supply original Unix-millisecond `sentAt`; missing
timestamps are stale, never replaced with receipt time. Named groups require
verified watch-UID membership; a room title alone never establishes membership.

Format: `#weartakcommand<name> key=value key2="value with spaces"` (a space after
`#weartakcommand` is also accepted). See [chat enrollment](ENROLLSERVER.md) for
credential syntax, update behavior and plaintext/purge limitations.
Supply paired `username=` and `password=` arguments to enroll without typing on
the watch. Quote passwords containing spaces; quoted passwords preserve their
surrounding spaces. Inside quotes, escape backslashes as `\\` and quotes as `\"`;
`!` and `_` are literal.

| Command | Arguments | Effect |
| --- | --- | --- |
| `enrollserver` | `name, address, port[, username, password]`, or legacy `name`, `url`, optional `port`, paired `username`/`password` | With credentials, adds or updates a matching server and starts normal registration using encrypted UI storage. Without credentials, prompts on the watch. Any allowlisted channel is accepted; locks and MDM server authority win. See [security and purge limits](ENROLLSERVER.md). |
| `setcallsign` | `callsign` (1-32 letters, digits, space, `.`, `_`, `-`) | Sets the callsign. Refused when settings are managed by the phone. |
| `setlocationinput` | Exactly `input=enhancedgnss` or `input=gnss` | Selects real Fused Location or satellite-only Internal GNSS respectively. Persists the same setting as Reporting, invalidates prior-input fixes and starts fresh acquisition without fallback. Refused when settings are phone-managed. The acknowledgement confirms the selection, not a successful position fix. |
| `setpdl` | One positional argument: `community` or an encoded SharedKey v2 PDL | Validates the selected identity and expiry, atomically saves source/custom identity, then recreates Ditto with the new identity. Refused when network settings are locked or phone-managed. Does not change the Ditto enable toggle. |
| `toggleditto` | Exactly one positional `on` or `off` | Persists the same enable setting as the UI, preserving selected identity and normal runtime gates. Repeated on/on or off/off is safe. |
| `enabletakservers` / `disabletakservers` | None | Explicitly enables/disables the TAK master switch, preserving individual servers and credentials. |
| `enablemulticast` / `disablemulticast` | None | Explicitly enables/disables multicast, preserving its address and port. |
| `usep12` | `<hex> server=<configured-id> [password="..."]` | Installs one validated client P12 on exactly the existing server ID. Missing/unknown ID or managed network settings changes nothing. Standard Base64 is also accepted. |

For example, `#weartakcommandsetpdl community` selects the unchanged built-in
Community identity; `#weartakcommandsetpdl <PDL>` selects a validated Custom PDL.
There is no key=value form or `enabled` argument. Custom PDL values may contain at most
16,384 characters (17,408 characters for this command); other command limits stay
unchanged. Replies contain only source, the first eight app-ID characters, UTC
expiry: `Ditto updated: source=community app=12345678 expiry=2027-01-01T00:00:00Z`.
`Ditto update failed` means saved settings could not be applied or the service
failed to start, not that the previous settings were restored. Normal companion,
permission and application-license gates still suppress sync when appropriate.
PDLs are credentials: use a trusted encrypted direct-chat transport; the sender,
TAK server and chat history on other clients may retain the original command.
WearTAK does not log or echo PDL values. Invalid/expired identities change nothing.
All commands also work in groups (with watch membership) and All Chat
Rooms. Outcome replies go back to the originating conversation,
including the verified original participant roster for named groups. Broadcast
or group provisioning exposes the original PDL to every recipient; only send a
custom identity this way when that entire audience is authorized to possess it.
Ditto chat executes these opt-in, allow-listed commands with the same freshness,
recipient and shared replay checks; other plugin/configuration/package dispatch
remains disabled on Ditto ingress.

`#weartakcommandtoggleditto off` received over Ditto attempts a native originating-room
acknowledgement before shutdown, explicitly stating that delivery is not confirmed.
Once disabled, `on` must arrive through another active route or the UI.

The four network-master commands accept exactly these forms:
`#weartakcommand enabletakservers`, `#weartakcommand disabletakservers`,
`#weartakcommand enablemulticast`, and `#weartakcommand disablemulticast`.
They are idempotent explicit settings, not toggles. Accepted changes atomically
save the same local-choice markers as the watch switches. Network locks, active
phone management, or a present explicit Android managed restriction
(`select_data_endpoints` for TAK; `multicast_service_enabled` for multicast)
refuse either direction, even when the managed value matches the request.
Missing restrictions do not override a choice; malformed present policy fails
closed with a reason and log.

Both disable commands require a warning written on the originating path before
any setting changes. TAK acknowledgement waits for socket write and flush;
multicast waits for datagram send; Ditto waits for its local chat store write.
Normal and confirmed TAK output share a per-connection serializer, without holding
the connection lifecycle monitor during I/O. The TAK warning wait is limited to
five seconds; timeout or cancellation refuses the change and closes an in-progress
warning socket without affecting a replacement connection.
These confirm local transport/store acceptance, **not end-to-end delivery**.
Queue-only Sit(x) and Companion reply paths cannot meet this requirement, so
disable commands arriving there are refused. A failed/throwing warning write
leaves the setting and marker untouched. Locks and policy are rechecked during
the actual atomic apply after the warning; a new restriction refuses the change.
Re-enable on the watch at any time, or via another configured, active command
transport such as Ditto or multicast. A disabled transport cannot receive its own
re-enable command. Existing `toggleditto` acknowledgement behavior is unchanged.

`usep12` always requires the exact configured stable server ID, never a host name,
implicit source server or all-server target. Omitted password uses the conventional
TAK default; explicit quoted passwords are retained exactly (including spaces).
Validation caps decoded P12 at 8 KiB / encoded input at 16,384 characters, requires
one private-key entry with a currently valid client certificate chain and matching
key, and initializes the TLS key manager before changing encrypted local storage.
Only that server's certificate fields change. Local apply failure/cancellation
restores previous storage and runtime; recovery failures are logged explicitly.
Success acknowledges installation, leaf SHA-256 fingerprint and earliest chain
expiry, **not** successful connection or server trust acceptance.

Typical 2.5-4 KiB client P12 becomes 5-8K hex characters. This fits the watch's
32-KiB gateway, 65,535-byte multicast receive buffer and 256-KiB Ditto document cap,
but is not a delivery guarantee: UDP fragments and sender/server/phone framing may
drop or truncate it. Use a reliable encrypted route and verify the status reply.
Managed `tak_server_cert` remains Base64, not hex.

**Security:** callsign allow-listing is not cryptographic sender authentication,
especially with the shared Community Ditto identity. Prefer trusted encrypted direct
chat. Per user choice, group/broadcast commands remain supported, exposing the P12
private key to every audience member and any storing server/mesh/other client.
The receiving app does not insert credential commands in its inbox, dispatch them
to plugins, log their text, or echo keys/passwords in replies. It cannot erase the
sender's or another system's history; rotate credentials after unintended exposure.

Profile commands take the same values as the settings screens (case-insensitive) and validate everything before changing anything, so one bad value changes nothing:

| Command | Argument |
| --- | --- |
| `setteam` | `team=<name from the team list>` |
| `setrole` | `role=<role name>` (the role category is set automatically) |
| `setheight` | `height=5'8"` (feet 1-8, inches 0-11; `5'8` and `5-8` also work) |
| `setweight` | `weight=<1-1000>` |
| `setbirthyear` | `birthyear=<1900-current year>` |
| `setsex` | `sex=<Male, Female, N/A>` |
| `setusertype` | `usertype="Coalition Civilian"` |
| `setpants`, `setuniformpantslengthinches` | `pants=<20-50>` (inches) |
| `setwaist`, `setwaistsize` | `waist=<20-60>` (inches) |
| `setprofile` | any combination of the fields above plus `callsign` |

The callsign, team and role fields (including in `setprofile`) are refused when settings are managed by the phone.

Allowed Callsigns uses the same full-screen text-entry page as other settings
when adding or editing an entry, with the standard confirm/cancel controls instead of a
dialog. Tapping an existing entry opens the keyboard with its current value.
Keyboard **Done** also validates and saves the callsign, so saving does
not depend on a button behind the keyboard. Invalid (outside the existing
1-32-character callsign format) or case-insensitive duplicate entries stay on the
entry page with an error. An edited callsign can retain its own name, but cannot
duplicate another entry. Cancel/back does not save. Removal is immediate.

`#weartakcommandloadprofile name=odin-weartak` applies a preset saved on the watch as `Android/data/<package>/files/presets/<name>.json`. Presets are provisioned with adb and are never part of this repository, because they can hold server credentials:

```json
{
  "profile": { "callsign": "ODIN-WEARTAK", "team": "Dark Green", "role": "K9", "height": "5'8\"", "weight": "150" },
  "servers": [ { "name": "NCTAK", "url": "takserver.nctak.gov", "port": 8089, "username": "user", "password": "secret" } ]
}
```

The profile is validated and applied, then each server is added through the normal add-server path (servers already present are skipped). Names are lowercase letters, digits and `-`. The reply says only how many servers were added. Refused when network settings are locked.

Presets may also carry `"unitPrefs": {"rab_rng_units_pref": "0"}` (display units, see
**Display units** below) and `"customAlerts": ["Pick me up", "Help"]` (saved Manual Alert
labels, merged into the watch's list; duplicates and built-in names are skipped, at most 20
labels of up to 60 characters). An invalid value in either field rejects the whole preset
before anything is applied.

**Settings > Tool Preferences > Plugins > Commands > Profiles** lists saved preset
files and opens a read-only view of their profile fields and server names, hosts,
and ports. Passwords, usernames, and unknown fields are omitted; opening a profile
does not apply it or connect to its servers. Missing presets show an empty state;
unreadable, malformed, or oversized (over 64 KiB) files show an error instead of
raw JSON or credential-bearing parser errors. Reopen Profiles after changing files.
The long command aliases use the same fields and ranges as their short forms:
`#weartakcommandsetuniformpantslengthinches pants=30` and
`#weartakcommandsetwaistsize waist=32`.

The watch replies in chat with the outcome. Callsigns are not authenticated:
another participant on the server or anyone able to inject multicast on the LAN
can impersonate an allowed callsign. Neither recipient UIDs nor replay filtering
prove origin; only enable this convenience feature on a trusted, scoped network.
Multicast is plaintext. Never put passwords, enrollment secrets or other
credentials in chat. Server enrollment still prompts on the watch and uses the
normal TLS/enrollment path; it does not accept credentials through either chat
transport. Command audit messages omit argument values, but ordinary chat and
packet diagnostics are not a secret-storage channel.
### WEARTAK-112: explicit shared-point deletion

**Owned watch pins versus received points.** Delete/Last/Clear All removes local
watch-created pins immediately, stops their active guidance and clears pending
live updates. Only pins with persisted local-origin ownership also send a
best-effort `t-x-d-d` request to currently connected TAK/SITX/multicast/plugin
routes, or exclusively through a negotiated connected Companion gateway.
Each request has an independent event UID and links the exact original pin UID
with `relation="none" type="none"` plus `__forcedelete`, valid point fields,
current time/start and 24-hour stale. This follows
[ATAK's delete importer](https://github.com/deptofdefense/AndroidTacticalAssaultKit-CIV/blob/889eee292c43d3d2eafdd1f2fbf378ad5cd89ecc/atak/ATAK/app/src/main/java/com/atakmap/android/cotdelete/CotDeleteImporter.java).
Peers may ignore a request; enqueue is not proof of remote removal.

There is no new offline CoT deletion queue or reconnect replay. If no route is
ready, deletion still completes locally; no unrelated peer or global wipe is
sent. Existing transport queues retain their existing semantics. Ditto uses its
existing author-owned persistent tombstone and retry ledger, never a raw delete
CoT document. Received points remain local-only except their existing eligible
paired-phone mirror removal below. Incoming deletions never rebroadcast.
Older Companions without a negotiated gateway keep that mirror-local behavior;
the watch does not bypass a connected Companion by using direct routes.

Both peers add an opaque `payload.revision` (UUID) to live marker envelopes.
Existing marker fields and XML remain unchanged; peers without this addition can
still receive points but cannot authorize explicit deletion.

```json
{"msg_type":"marker_delete","payload":{"targetUid":"shared-point-uid","revision":"previously-exchanged-revision"}}
```

Deletion means **remove the exact shared revision**, not delete every copy of a UID.
Only the current bonded, services-ready BLE connection can send/apply this envelope.
There is no timestamp fabrication, CoT `t-x-d-d` importer invocation, network delete
broadcast, offline queue, startup snapshot, or reconnect replay.
The receiver answers each well-formed deletion with a `marker_delete_ack` envelope
(`targetUid`, `revision`, `result` of `applied` or `rejected`) on the same connection.
The sender matches it to the pending deletion for that exact uid, revision and
connection generation, and records the outcome (`PointDeletionAcks`). A deletion with
no matching ack within 10 seconds is recorded as timed out, and an unknown, duplicate or
previous-connection ack is ignored. An ack confirms only that the peer applied or
rejected the deletion, not delivery to any TAK server. Peers that predate this ack
never answer, so their deletions report a timeout.
The revision must match a supported marker exchanged in this connection. New
shared updates replace it; unknown, duplicate, superseded, malformed, and
previous-connection deletions are inert and logged without payload contents.

On phone deletion, the watch removes the `companion-phone` map/Bloodhound copy
using its stored event-time watermark. Other-source copies survive. An original
watch-dropped point with the matching shared revision is also removed unless a
local edit/location update is pending; its dispatch backlog is cleared without
echoing a delete. Navigation to a removed source copy stops.
On watch deletion, the phone removes only the observed, supported shared marker
whose exported content still matches the exchanged revision, using ATAK's local
`removeFromGroup()` API, with echo suppression. Unshared native items, alerts,
PLI, routes, shapes, and groups are not eligible.

The existing watch received-marker Delete UX still removes all local copies of
that UID; it relays only when the selected visible copy belongs to the companion.
Dropped-marker Clear/Last/All removes local points immediately and emits
per-point explicit BLE deletions only for points shared this connection.
Legacy expired marker clear CoT is not generated. `MARKER_CLEAR` now carries only
the explicit owned-pin linked request; its policy excludes raw Ditto and legacy
Companion marker envelopes.
Bloodhound menu Remove remains local target dismissal, not map deletion.
Transport enqueue is best-effort, not delivery confirmation: both apps must be
updated together and hardware acceptance is still required.

Regression coverage: `CompanionPhoneMarkerLifecycleTest`, `SourceAwareCotStoreTest`,
and companion `PhoneMarkerRelayTest` cover revision/session rejection, source
preservation, navigation cleanup, replay watermarks, echo release, and write failures.
`CotDispatchServicePointDeletionTest` verifies ownership-scoped individual/Clear All
deletion, active-guidance/backlog cleanup and best-effort routing with mocked endpoints.
`CotEndpointDispatcherTest`/`CotDispatchPolicyTest` verify own-pin clear CoT uses only
eligible direct routes or the exclusive negotiated raw-CoT gateway, never raw Ditto or
the legacy revision-based marker-delete envelope. No real deletion is sent by these tests.

Paired-device acceptance (not performed by JVM tests): create a phone point, start
watch Bloodhound, then delete in ATAK: the companion-source point/target disappears
and navigation stops. Repeat with a watch-dropped point deleted on the watch and
verify the ATAK marker/tracking disappears. Edit the watch-origin marker in ATAK
and repeat both directions. With a same-UID TAK copy present, verify deleting the
phone copy leaves the TAK copy intact. Replay the old deletion after a newer
shared edit or reconnect: the newer point must remain, with no echo deletion or
startup replay. Unsupported PLI/emergency/route/shape/group items must remain.
With a safe watch-owned test point on a third TAK/network peer, watch Delete/Clear All
requests best-effort removal through the eligible direct or negotiated raw-CoT route.
Received-point dismissal and legacy companion mirror deletion remain local/mirror-scoped;
verify unrelated peer-owned points remain untouched. Remote peers may ignore requests.
### Remote emergencies

Incoming `b-a-o`, `b-a-o-*`, and ATAK's standard `b-a-g` CoT emergencies share one active
alert state for the map and Bloodhound. A yellow warning symbol distinguishes them from
ordinary contacts and markers, remains visible regardless of those map filters, and opens
the alert's Bloodhound details. Alerts appear first in the picker, with the sender and
emergency category.
Choosing navigation does not send an assignment acknowledgement or automated chat.

TAK server, SITX, companion IPC, and paired companion BLE ingress use the same parser
and lifecycle handling. The ATAK companion plugin forwards incoming phone emergencies
and cancellations only while a bonded watch is connected, using a `phone_emergency_cot`
envelope with original CoT XML in `payload.cot`. BLE alerts appear under source
`Companion ATAK` and do not require a separate TAK connection on the watch.
Disconnected alerts are not queued for replay by the plugin.
Disconnecting does not clear alerts already received by the watch. A cancellation
received after reconnect removes matching alert copies from both BLE and direct TAK
ingress; older replayed alerts cannot resurrect them. Local dismissals remain local
and suppress refreshes until a matching cancellation ends that alert session.
Asynchronous ingestion failures are logged as processing errors, not successful delivery.
ATAK's `*-9-1-1` alert UID, parent link, emergency text/type and cancellation flag are
supported, as are WearTAK's biometric alert details. Own alerts are ignored. Source-owned
copies are deduplicated by CoT UID; cancellation removes only emergency copies at or before
its event time, not the sender's ordinary PLI or markers. Cancellation timestamps survive
process restarts to reject delayed replay. Passing the sender's CoT stale deadline marks
an alert **Stale** in both the map label and Bloodhound instead of removing it. Valid
already-stale incoming alerts are accepted as last-known emergencies. A newer refresh
updates the deadline and clears Stale while fresh; explicit cancellation removes the alert.
A missed cancellation can therefore leave a stale alert visible; Stale does not mean
the emergency ended or that its location is live. Explicit local dismissal is still available;
source disconnect/removal clears only that source and preserves other copies.

Alerts without a usable location remain readable in Bloodhound, but are not placed on
the map and cannot guide navigation. A location-less refresh can retain that source's
previous valid location, marked as last-known when stale. **Dismiss locally** hides the
current emergency from both map and picker without sending any cancellation to the sender.
The persisted dismissal suppresses ongoing repeats and refreshes, including after restart.
Only a sender cancellation at or after the latest observed emergency update ends that
dismissal; a newer reactivation may then reappear, even when ATAK reuses the same UID.
Delayed older cancellations do not clear a dismissal. Ordinary point deletion is unchanged.

Receive/apply/stale-transition logs record why an alert was ignored (self, cancelled, older),
distinguishing missing ingress from rejected alerts during hardware verification.

The manual alert picker also supports ATAK's standard `911 Alert`, `Ring The Bell`,
`Geofence Breached`, and `In Contact` types alongside the existing custom options. These
map to `b-a-o-tbl`, `b-a-o-pan`, `b-a-g`, and `b-a-o-opn`, respectively, so standard
alerts can be sent and received without changing custom alert behavior. This feature builds
on the remote-emergency reception and display work pending in gov MR!327.

Manual alerts appear in a scrollable, single-column list with one-line labels. Tap a
built-in or saved alert to send it immediately. The `+` row opens **Type Custom Alert**;
saving adds that alert to the list for future use. Presets and custom alerts sort
alphabetically together. Tap a custom alert's right-side trash button to open its
delete confirmation. Alerts without an available route continue to use the existing
pending-alert queue.

New saved manual alerts allow up to 60 UTF-16 code units after trimming and
collapsing whitespace, with at most 20 saved entries. ISO control characters
(including newline and tab), case-insensitive duplicates and built-in names are
rejected without truncation. Existing persisted entries are retained verbatim,
including legacy longer entries; they remain individually deletable. The limits
are validated on additions, not applied as destructive load-time migration.
This rule does not change chat presets.

Alert kinds: the four ATAK standard types above use their own CoT types; every other built-in
(Gate Runner, Gunshot, Injury, UAS, Vehicle) and every typed or saved custom
alert is sent as `b-a-o` (shown by TAK Server as kind `sos`), with `alertCategory` "Manual
Alert" and `alertPriority` 1. Cancels are `b-a-o-can` for every kind, reusing the alert UID.
Each alert UID is delivered once per route and state, even when later GPS fixes arrive.

### Display units

**Settings > Tool Preferences > Display Preferences > Units** selects **Metric**
(Meters/Kilometers, default) or **Imperial** (Feet/Miles), the labels ATAK uses for its Range
Units. It applies to Contacts and marker distances, Bloodhound distances and the radius
picker, routes, the Bloodhound complication and the map scale bar. Values are stored in
meters; the setting only changes display. Metric shows `m` below 2000 m and `km` above; Imperial
shows `ft` below 5280 ft and `mi` above (ATAK's default `rng_meters_display_pref` and
`rng_feet_display_pref` thresholds). Bearings are labelled true (`°T`).

The value is stored under ATAK's key `rab_rng_units_pref`: `"1"` Metric, `"0"` Imperial.
ATAK's `"2"` (Nautical Miles) is shown as Imperial. It can be set from presets (`unitPrefs`),
managed configuration (`rab_rng_units_pref` restriction), and the bonded Companion app: a full
`set_settings` may include `payload.unitPrefs`, and a live
`{"msg_type":"unit_prefs","payload":{...}}` message (also `msgType`) applies a change and is
answered with `unit_prefs_ack` (`ok`, `error`). Companion sends ATAK's seven Unit Display
Format keys as strings; the watch reads only `rab_rng_units_pref` and ignores the rest. The
settings snapshot echoes `unitPrefs: {"rab_rng_units_pref": "0"|"1"}`. Live changes are
accepted only from the current single-watch bond, not from gateway sessions; the last writer
wins.

Chat presets in the reply picker submit the selected text immediately to that conversation.
Typed chat text still uses the existing compose-and-Send flow. The picker keeps the
conversation UID so incoming list changes cannot redirect a preset to another recipient.

Saved chat presets follow the Manual Alert pattern in both the New chat and in-room preset
lists: one-line rows, with built-in and saved presets sorted alphabetically together.
The `+` row opens **Type Custom
Message**; **Save message** stores it without sending. Saved presets are de-duplicated against
built-ins (case-insensitive), limited to 20 entries of 60 characters, and deleted by tapping
the right-side trash button and confirming. Built-in entries have no delete button.
Opening either editor from a scrolled picker reveals its first field or
confirmation heading, rather than carrying the old list offset into the form.

PLI remarks report exertion as a whole percent (`Exert:45%`), matching the watch display. The
`<exert>` detail stays a 0..1 fraction for the ATAK physiology plugin.

The main-screen physio chip is always tappable. With Physiological Services off it shows
**Physio off** and opens settings; while the watch reports not worn, values show **Not worn**.
Worn-state transitions and their source are logged under the `WearTAK-DeviceWorn` tag.

When the companion phone BLE session is ready, outbound PLI, chat, markers, and alerts
use the phone uplink only; the phone handles onward TAK, SITX, and multicast delivery.
The watch resumes its directly configured routes when the companion session disconnects.
The connection complication reports **Phone uplink active** while that route is in use.

The phone's Multiwatch Gateway uses a transient, session-qualified BLE negotiation; it
does not add a watch mode selector or settings UI. During that session the watch keeps
its own UID, callsign, team, role, report interval, and server/P12 configuration and does
not accept phone profile writes. Gateway CoT is limited to supported map points, user
positions, emergency/cancellation events, and GeoChat. Incoming chats are checked against
the watch's own UID/callsign and explicit recipients; unsupported CoT and profile/control
messages are rejected. The Gateway session and duplicate-event ledger clear when BLE is
no longer services-ready, after which the existing directly configured routes resume.

`WearTakApplication` initializes shared repositories and the CEP/plugin infrastructure. `DataRepository` is the main persistent state and settings hub, based on DataStore and Kotlin Flows. Newer components use Flow/StateFlow for reactive updates; some existing components communicate through EventBus.

The important runtime path is:

```text
Watch sensors, user actions, and integrations
                 |
                 v
       repositories and event streams
                 |
                 v
  ReactiveServiceManager / service layer
                 |
                 v
    CotDispatchService -> TAK/SITX/Ditto endpoints
```

Key areas of the source tree:

```text
app/src/main/java/com/aitech/weartak_civ/
├── presentation/       Wear OS activities and Compose UI
├── service/            foreground services, connectivity, and sensor orchestration
├── data/               DataStore-backed state, repositories, models, and secure storage
├── cep/                CEP rules, configuration, event bus, and alert actions
├── integration/        managed configuration and plugin-host integration
├── ipc/                CoT IPC models and serializers
├── complication/       watch-face complication providers
├── tile/               Wear OS Tile provider
├── receiver/           boot, update, and alarm receivers
├── events/             legacy EventBus event types
└── WearTakApplication.kt
```

## Development notes

- Use `./gradlew :app:assembleDebug` for a focused compile/package check.
- Enable optional services through the app settings or managed configuration; do not start a service directly without considering its `ServiceConfiguration` prerequisites.
- Runtime configuration defaults live in `Constants.kt` and persistence keys/state are in `data/DataRepository.kt`.
- CEP rules and related runtime configuration are under `app/src/main/res/raw/` and `cep/`.
- The repository currently has unit and instrumentation test dependencies, but it does not contain a broadly established test suite. Add targeted tests with feature changes where practical.

For live Android logs, use:

```bash
adb logcat
```

To inspect app-private files on a debuggable install:

```bash
adb shell run-as com.aitech.weartak_civ ls files
```

## Security and data handling

WearTAK-WearOS handles potentially sensitive location, health, communications, and credential data. Treat device logs, exported app files, managed configurations, and build artifacts accordingly. Configure secure TAK endpoints and credentials appropriate to your deployment, and review required Android permissions before field use.

## Contributing

Unlimited Rights granted to TPC

Keep changes focused and validate them with the narrowest relevant Gradle task or test. When adding or changing a service, account for its manifest declaration, permission requirements, persisted enablement, and registration with the service orchestration layer.

### Point of Contact: Alex Gorsuch on chat.tak.gov or Signal


#### Bloodhound picker controls and artwork

Each picker row has a type/name/subtitle, a grey crosshair (**Show on map**, no
navigation activation), and a green ATAK hound (**Bloodhound**). Routes open a
**Start nearest / Start from beginning / Cancel** chooser; other located targets
start immediately. Active rows turn amber, show route progress and remaining
distance, and replace the hound with a red **Stop** button. The row body still
opens details. Route crosshairs fit the whole geometry within the round watch;
point/contact/marker crosshairs center the map at the target.

The bundled `atak_nav_bloodhound.png` and `atak_ic_crosshair.png` artwork comes
from [deptofdefense/AndroidTacticalAssaultKit-CIV](https://github.com/deptofdefense/AndroidTacticalAssaultKit-CIV)
(`atak/ATAK/app/src/main/res/drawable-xxxhdpi/nav_bloodhound.png` and
`drawable-hdpi/ic_crosshair.png`), licensed GPLv3. Distribution/licensing review
of this artwork is pending with the project owner.


### Stationary location battery saving

With location and activity recognition enabled, a stationary location latch (a usable still fix with significant-motion monitoring) and STILL activity at 50% confidence must agree for 30 seconds before GPS acquisition and self PLI use a minimum 60-second interval. Slower configured, docked, or network-power intervals are preserved. No additional sensors or polling are enabled. Motion, activity changes, screen wake, and user interaction restore the normal cadence; alerts and explicit sends bypass the stationary delay. Self PLI advertises at least twice the effective interval plus 15 seconds of validity, including an announcement when the stationary interval increases. Existing off-body transport policy is unchanged.
