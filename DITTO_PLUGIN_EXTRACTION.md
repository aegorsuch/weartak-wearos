# Ditto plugin extraction inventory

## Current direction

Update and restart WearTAK Core before installing the optional Ditto plugin.
The plugin requires Core and owns provisioning, settings, and Ditto storage;
no identity or point-history transfer is required. Earlier migration and handoff
sections below describe inactive work, not the intended release path.

The bundled Ditto service and SDK dependency have been removed from the current
source. The plugin-only profile is enabled. The integrated candidate pins
canonical SDK commit `8d1b8b46437136f7b9deea6aaa6973cd84925793` with the
existing transport overlay. Earlier debug and release unit suites passed with
candidate SDK commit `6f295be6ba52fd4e4d67d1cc3813ca22608f087c`, but do not
validate this integrated candidate. TAK.gov CI, lifecycle/device validation,
and review remain required. Marker routing uses the
existing authenticated `ditto-v1` transport, with stable journal message IDs.
`CHANNEL_SUBMITTED` records local submission, not durable storage or peer
delivery. Disconnect or failure after admission leaves the result uncertain.
Independent marker routes are offered separately while an installed, enabled
Ditto plugin reconnects; their offer state is persisted to avoid replay.
The plugin-only profile uses authenticated host device status for
the TAK UID and does not provide the host's Community Identity binder. Point
ingress protects host-owned markers using the generic marker journal.
The direct profile can retire and re-register transport and point sessions on
the same authenticated binding. Companion and power suppression request plugin
stop and restart on that binding. Alert submission uses a stable activation
ID and captured payload, retries only known pre-admission refusals, and fences
uncertain outcomes. Independent routes are offered first. Per-UID fences reject
stale queued alerts and clears and prevent a new activation overtaking an
uncertain earlier submission. Captured clears persist for retry after plugin
route recovery without replaying independent routes. Uncertain submissions
are retired, never replayed, only after an authenticated replacement session
from the verified signed plugin build `5.8.4-p35+d981f7a5` (version code
`5840035`). The prior admission must also have recorded a verified session.
Unknown, older, or unverified builds cannot establish this proof; other builds
are rejected, including later version codes. Future canonical plugin updates
must be revalidated and added to Core's compatibility policy before
installation.

The inventory and migration discussions below are historical. The current
direction does not transfer credentials, settings, or history.

Target repositories are established: internal-access
[`weartak-plugins/weartak-plugin-ditto`](https://git.tak.gov/weartak-plugins/weartak-plugin-ditto)
and public [`aegorsuch/weartak-plugin-ditto`](https://github.com/aegorsuch/weartak-plugin-ditto)
mirror. Approved branch `aegorsuch-weartak-313-ditto-plugin-extraction` exists
in the local plugin worktree; scaffold changes are uncommitted. No vendor
material was uploaded; the public mirror remains untouched.

## Current ownership

- **SDK and identity:** `app/build.gradle` pins `live.ditto:ditto:4.14.6`.
  Release CI supplies `DITTO_COMMUNITY_IDENTITY`; `DittoCommunityLicenseHelper`
  validates a SharedKey v2 PDL and offline token. Custom PDL selection is
  separately persisted. Neither value belongs in source control or plugin
  diagnostics. The app now packages Ditto's published binary-license notice
  under `assets/licenses/ditto-sdk/LICENSE.txt`.
- **Startup and lifecycle:** `WearTakApplication` owns app repositories and
  plugin initialization; `ReactiveServiceManager`, `ServiceConfiguration`,
  and `DittoService` gate Ditto startup. `DittoService` owns SDK initialization,
  subscriptions, store observers, identity reconfiguration, sync stop, and
  foreground-service lifecycle.
- **Transport discovery and policy:** `DittoNetworkMonitor`,
  `DittoTransportPolicy`, and `DittoBleDiscovery` manage network-dependent
  transports, bounded BLE discovery/recovery, radio/permission gates, and
  peer status. `DittoPowerMonitor` and `DittoPowerPolicy` gate sync by power.
- **CoT, PLI, points, and chat:** `DittoSubscriptions`,
  `DittoStoreObservers`, `DittoCotReceiver`, `DittoCotDocument`,
  `DittoChatDocument`, `DittoPointIngress`, `DittoPointLedger`,
  `DittoPublicationRetry`, `DittoTrackExpiry`, and `DittoChatRetention`
  implement subscriptions, document conversion, receive, deduplication,
  deletion ownership, retry, and retention. `CotDispatchService` and
  `CotEndpointDispatcher` send outbound CoT/PLI/chat to Ditto.
- **Host integration:** `DataRepository`, Ditto command handlers, settings,
  connectivity/status UI, diagnostics, and `AndroidManifest.xml` own the
  enablement/identity controls, service gates, user-visible state, and
  Bluetooth/Wi-Fi/network permissions. Companion is optional: direct Ditto
  pauses only for an active Companion connection.

## Existing plugin contract coverage

The pinned plugin SDK (`ci/plugin-sdk.json`) and core already provide:

- Authenticated plugin admission and lifecycle cleanup through
  `PluginManager`/`PluginConnection`.
- Opt-in inbound/outbound CoT and typed chat over Transport V1.
  `PluginTransportHostV1Impl` normalizes inbound messages through the host CoT
  ingestion path and fences plugin-originated messages from being echoed back.
- Plugin-owned route status and generic hosted UI/settings actions.
- Host services V1 for bounded map actions and authenticated TAK Server REST;
  it does not expose Ditto credentials.

This is enough to connect a separately implemented transport to host CoT/chat;
it does not make that transport a replacement for Ditto's native data behavior.
No native Ditto document/store, point-ledger, PDL migration, or host-owned
mesh-runtime contract was found in the inspected plugin interfaces. A plugin
would need to own its Ditto SDK/runtime, provisioning, state, and permission
handling, or a separately designed host contract would be needed for any
core-owned native data behavior.

The user approved automatic, authenticated, on-watch provisioning of the
existing Community identity when the plugin is enabled. An additive, scoped
Community identity control is being developed; it accepts only the Ditto
plugin's exact package, unique UID, and pinned release signer, revalidates the
binding, and releases no identity until a verified ownership gate exists.
The host currently always denies that gate: no Community identity is sent and
the new control does not yet enable automatic provisioning. The plugin and
host have synthetic-tested package/UID/signer checks and stale-session fencing,
but still need device validation before use. The plugin has an asynchronous
client exercised with synthetic responses; its production ownership gate
prevents real reads, and successful test identities are discarded. The plugin
worktree replaces its temporary wire interface with the host-services SDK built
from pinned core source; device validation remains pending.
This design does not need ATAK Companion and does not add
credentials to the plugin APK. Synthetic host tests cover caller
UID, signer identity, session revocation, expired/invalid identities, and the
denied gate; they do not exercise transfer of the real identity or a device
Binder.

The host-services contract source is available in core revision
`07d8649559dee44543f82cd1895c726fd904e64f`. The plugin worktree verifies the
SDK AAR contents against pinned hashes. Offline debug and release builds and
117 tests per variant passed using the pinned SDKs; the local release was
unsigned. CI for these uncommitted changes and device-level Binder
interoperability remain unverified.
### Coexistence and handoff

`CotDispatchPolicy` currently routes standalone chat, marker, and emergency
messages to both core Ditto and an available generic plugin transport. If a
Ditto plugin registers while core Ditto is active, both paths can publish the
same messages. Generic plugin availability does not identify a Ditto runtime
or provide an ownership handoff, so suppressing all plugin routes would also
disable unrelated transports. Do not register an active Ditto transport until
an explicit, tested ownership and rollback strategy prevents duplicate mesh
publication.

`DittoService.isRunning` is not a shutdown acknowledgement: `onDestroy`
clears it before `stopPeer()`, and local sync state is cleared even when the SDK
stop fails. Core now records a separate stop outcome: identity replacement and
explicit disable cannot report success after an unacknowledged stop. SDK stop
failure, a missing owned instance, or an exception during owned resource cleanup
also latches a process-local failure gate. Identity-change cancellation or timeout
before the service and its workers acknowledge shutdown latches the same gate.
An exception or cancellation during native `close()` also latches the gate;
the instance's resource state is then unknown and it must not be restarted.
Initial sync and power-driven sync resumption decline to start when they observe
the reconfiguration or shutdown-failure gates. These checks are not an atomic
cross-thread ownership barrier: a callback already past its check can still
finish before shutdown.
The service manager and direct sync
startup refuse restart, and subsequent identity/toggle requests fail without
replacing the instance or overwriting saved enablement intent. The gate lasts
until process restart; it is not a durable ownership record or a cross-process
shutdown acknowledgement. A future handoff must not grant plugin
ownership merely because either flag became false. It needs an explicit
successful quiescence acknowledgement, fail-closed handling for stop errors,
and crash/restart and rollback tests without overwriting the user's setting.

Ditto's [4.14.6 Android API reference](https://software.ditto.live/android/Ditto/4.14.6/api-reference/ditto/live.ditto/-ditto/index.html)
describes `stopSync()` as stopping all network transports and `close()` as
shutting Ditto down and releasing resources; it requires `close()` before
recreating an instance with the same persistence directory. It does not specify
the timing of native quiescence or cross-process handoff guarantees; those still
require vendor clarification or direct validation before granting ownership.

Explicit disable now holds the process-local restart gate while awaiting both
the cancelled service scope and its update thread, as identity replacement
already did. A timeout, cancellation, or failed shutdown fences restart and
restores the saved toggle where applicable. These worker joins do not prove
native transport quiescence or prevent cross-process ownership.

Candidate handoff sequence (not implemented): authenticate a specific plugin
session and confirm its prerequisites; fence new core publications and service
restarts without changing the saved enablement setting; settle pending writes
and stop the core peer; grant the plugin only after a successful stop
acknowledgement; then permit plugin registration and publication. On failure,
do not grant ownership. Resume core only after the plugin has demonstrably
stopped; if either side's shutdown is uncertain, fence both and surface an
error rather than risk concurrent peers. Persist enough non-secret handoff
state to recover conservatively after process death. This still needs an
authenticated, versioned host/plugin contract and end-to-end validation.

Core regression coverage now exercises failed SDK stop, missing owned instance,
cleanup failure, worker-join timeout, repeated identity requests, saved-toggle
rollback, and automatic-restart suppression. The exact Ditto and
ServiceConfiguration test-class selection passed offline: 212 tests, zero
failures, one skipped. This is core-only synthetic validation, not plugin
handoff, SDK shutdown, or device validation.
An additional direct service-creation rejection case subsequently passed in the
focused identity suite, verifying no native startup access after failed shutdown.
The focused identity, companion-lifecycle, and power suites subsequently passed
31 tests including disable wait, timeout, and cancellation coverage.

The plugin now authenticates initialization against the actual Binder caller UID,
claimed package membership, signing match, and granted signature-level
`BIND_PLUGIN` permission. Later operations recheck the authenticated UID/package,
permission, and signing; unbind/destruction invalidates old handles. Inert
pre-initialization getters remain available for handshake discovery. This trusts
same-signed permission holders, not a core-specific allowlist, and cannot
distinguish packages sharing a UID. It does not provide an ownership lease or
process-death acknowledgement.

Core source ordering is verified as metadata/API discovery, initialization,
callback registration, start, host-side transport activation, then settings
connection. Plugin synthetic tests exercise the V2/V3 plugin-call sequence.
Plugin debug build and 78 tests passed after authentication changes; the
subsequent handshake-order test passed in the focused service suite. Actual
Binder calls, permission ownership/grants, signing lineage, reconnect behavior,
and device interoperability remain unverified. No transport is registered and
the plugin runtime remains disabled.

The plugin now has uncommitted route-status groundwork for a stable `ditto`
route: an authenticated host control receives only disconnected/runtime
unavailable, not a fictional connected or connecting state. Core can list that
reported route in its network checklist and icon-priority settings while the
plugin is loaded; no real on/off control or network status exists yet. The
host/device path is not yet verified, and the signed CI artifact predates this
change. Plugin debug and release unit suites passed with 98 tests each.

PLI and deletion require separate parity work: the core's native Ditto track
refresh is not a Ditto `mapitem` CoT send, and core-owned point deletion uses
an author-owned persistent tombstone rather than marker-clear CoT. Companion
availability currently selects the Companion route instead of direct routes;
standalone operation must not require it. Generic CoT delivery alone therefore
does not establish native Ditto parity.

Point migration must preserve the core ledger's owner, document revision,
terminal deletion, and pending write intent, plus the receive-side permanent
ownership/deletion watermark and replay protection. These records live in
core-private storage; another Android package cannot read them directly.
Shared-key writer ownership is an application rule, not a cryptographic
signature. Migration must not silently discard pending tombstones or make a
deleted point live again, including after rollback.
The plugin's isolated ledger does not migrate core data or acknowledge actual
Ditto writes. Its generation counter requires retry wake-up wiring equivalent
to the core's StateFlow; each plaintext preference store requires exclusive
ownership by one active instance.

## Plugin-repository scaffold and SDK build

The [`weartak-plugin-template`](https://git.tak.gov/weartak-plugins/weartak-plugin-template)
main branch was verified at `a4af9f1b9bd015310743a6b0aef4bb0b24d65bf6`.
It provides a standalone-watch app shell and a signature-protected bound plugin
service. Its build uses Android Gradle Plugin 8.7.2, Kotlin 2.0.21, compile SDK
34, minimum SDK 30, and JDK 17.

The template consumes WearTAK SDK AARs from `app/libs/`, but its README says
their redistribution terms are unverified. Do not copy those opaque AARs into
the Ditto repository. The core instead builds the pinned SDK from source using
`python ci/build_plugin_sdk.py`: source commit
`6f295be6ba52fd4e4d67d1cc3813ca22608f087c`, plus the Transport V1 overlay
with source SHA-256
`a818af15b3328b817b972e2fe81d9585cb54e9a952a0ec8e13936ee5987ee6a6`.
The generated release AAR is SHA-256-pinned per platform in `ci/plugin-sdk.json`.
This candidate source was verified in TAK.gov CI but is not yet a merged SDK release;
plugin activation remains gated.
No SDK AAR or Ditto SDK binary was copied into the plugin repository.

The development scaffold initially used the provisional application ID
`com.weartak.plugin.ditto.dev`. The user selected
`com.weartak.plugin.ditto` as the final application ID, now applied and checked
in the debug APK. These Android package IDs are not in-place upgrade compatible.
The scaffold has a signature-protected service that
advertises no functional Ditto capabilities and reports provisioning as
unavailable. It does not initialize Ditto, request mesh permissions, or enter
the running state. An isolated v2 Ditto-to-CoT decoder handles PLI, marker,
emergency, and native/extended chat documents. It rejects invalid documents,
malformed or oversized XML, and sensitive enrollment content. Isolated point
ingress and local ledgers preserve owner, revision, terminal deletion, and
pending intent using synthetic test data. Seven point tests, 15 existing unit
tests, and the offline debug build passed. A standalone native-track
publication gate was then added; six additional tests and the full 28-test
suite passed. Native track encoding and expiry, outbound chat and map/alert
encoding, and inbound chat freshness checks were subsequently added; the full
53-test suite and offline debug build passed after adding isolated replay
comparison and chat retention calculations. Four additional synthetic
cross-component tests cover chat, point deletion/retry, and track flows; the
full 57-test suite and offline debug build passed.
These components are not wired to live inputs or writes. The release variant
is disabled. Neither this code nor its application ID is verified for
deployment or connected to a Ditto runtime.

The plugin README documents building the pinned SDK from canonical source and
overlay without committing an AAR. Two independently built Windows artifacts
matched the pinned hash; the second was used for the successful plugin build
and test run. Linux and a completely disconnected clean build remain unverified.

An unwired lifecycle slice compiles against Ditto 4.14.6 in the unit-test source
set only. It calls `stopSync()` on a caller-supplied instance, blocks start,
serializes stop attempts per adapter, and does not treat successful return as
proof of quiescence. It constructs no instance; the service cannot activate it.
The debug APK contains neither the SDK-referencing bridge nor Ditto runtime or
native libraries, and no mesh permissions were added. Synthetic tests and the
debug build passed with 63 tests total; actual SDK lifecycle behavior and device
class loading remain unverified. Subsequent core-style at-most-once stop ordering
and an isolated coroutine-based point-publication retry worker brought the plugin
suite to 71 passing tests. Retry observes ledger generation and allowed-state
flows, backs off from 10 seconds to five minutes, and cancels its job on close.
It is not connected to the service, and cancellation alone does not acknowledge
completion of an in-flight write.

The cached Ditto
4.14.6 AAR matches its published module hash. An offline dependency probe could
not resolve its complete runtime graph; an online-enabled probe resolved all
34 debug runtime artifacts, possibly using cached files. A clean-cache build
was not verified. The public Ditto Binary License
restricts reverse engineering, while the plugin contains GPL-3.0-only
core-derived code. The user confirmed that the applicable Ditto terms permit
distributing the SDK together with WearTAK's GPL-3.0-only code. This is a user
confirmation, not an independent review of the written terms or a grant to
publish SDK material separately. SDK documentation has not yet established a
positive cross-process quiescence guarantee for ownership handoff.
Ditto's [legacy sync guide](https://docs-legacy.ditto.live/sync/start-and-stop-sync)
says `stopSync()` disconnects peers and pauses subscriptions, but does not state
when in-flight work or all transport resources are quiescent relative to return.
Do not treat that guide as a verified 4.14.6 cross-process handoff guarantee.

## Current identity provisioning

The core pipeline requires a nonempty `DITTO_COMMUNITY_IDENTITY` variable and
Gradle injects it into `BuildConfig`; repository source does not establish the
variable's protected/masked configuration, identity owner, or authorization
scope. The selected Custom PDL is stored under `ditto_custom_pdl` in the
app's regular Preferences DataStore; no separate encrypted PDL store is wired
here. Ditto parsing requires v2 SharedKey identity, app ID, shared key, offline
token, and expiry. Startup logs only whether a PDL is present and its length,
not its contents. No provisioned PDL or CI secret was read for this review.
Existing parser tests use synthetic credentials; the authorized-file test is
opt-in and was skipped.

## Private community subgroup groundwork

The user requested plugin-local group selection, with SpringLake as an example,
while retaining the exact existing community PDL and Ditto identity. The
agreed design selects one active group at a time: Community or a private group
joined using an invitation. This is application-layer content encryption, not
a replacement PDL, an SDK-authenticated submesh, or a new license.

Private membership needs an independently generated group key and opaque group
identifier; knowing a display name or the community PDL must not grant access.
Persist membership keys using Android Keystore protection, not host-readable
settings or plaintext preferences. Invitations contain credentials and require
deliberate, secure handling. Missing keys, encryption failure, or invalid
membership must block private publication rather than fall back to Community.

Group selection must not relabel pending writes, tombstones, replay state, or
cached documents from another group. The encrypted envelope must be distinct
from existing public native Ditto documents so legacy readers cannot mistake
it for a public location, chat, or map item. Actual schema and integration
remain subject to implementation and interoperability validation.

Encryption protects content, not radio visibility, peer discovery, or traffic
metadata. Every holder of a group's shared key is trusted with that group's
content; member removal needs key rotation and does not erase previously
obtained data. Plugin-local selection does not suppress independent core TAK,
SITX, multicast, or Companion routes and must not be presented as making those
transports private.

The plugin session implemented authenticated hosted group selection, internal
create/import APIs, encrypted membership storage in an app-private no-backup
file wrapped by Android Keystore, and unwired authenticated CHAT/LOCATION/MAP
envelopes. Synthetic encoder-to-encryption round trips, tampering and wrong-key
rejection, persistence failures, and selection/restart checks passed in an
88-test offline debug suite. Synthetic wrapping keys do not verify Android
Keystore or filesystem behavior on an actual watch.

The local plugin-owned, signature-protected MAIN/LAUNCHER activity now supports
group creation/selection and deliberate document-picker invitation import/export.
The core plugin menu uses this launcher path when hosted main UI is unavailable;
`externalSettingsActivity` does not itself select an activity route.
Invitations contain a group key, and a user-selected document provider may
upload them: provider warnings are not an authenticated transfer protocol.
Watch activity launch, signing grants, Keystore, and picker behavior remain
device-unverified.

Selected-group preparation now covers native chat, track/location, mapitem, and
alert encoders. Private preparation returns only an authenticated envelope;
failure does not return a public native document. Prepared outputs are single-use
and bound to persisted selection generation/scope; switching away and back
invalidates an older output. Ordinary local group switching preserves each
membership and sender's counters without the initial permanent selection lock.
Migration from the immediately preceding local v2 encrypted state to v3 preserves
memberships and counters. This is plugin development-state migration, not core
Ditto data migration.

Per-sender derived data keys limit attempted envelopes to 100,000, and wrapping
keys limit state writes to 1,000,000, with persisted accounting before output.
These limits do not establish production nonce safety, protect against rollback,
or prove crash durability and collision freedom. The final offline debug build
and 95 synthetic tests passed; APK inspection found no Ditto SDK or stop-bridge
references.

No SDK transport, subscription, network path, or group-isolated ledger/replay/
cache/outbox is active. Existing native encoders remain independent helpers;
future runtime must use the selected-group boundary, segregate those stores, and
recheck scope/generation at actual transport release. Local encryption tests do
not demonstrate private mesh interoperability or replay-safe delivery.

No subgroup runtime activation, actual credential access, new commits, pushes,
or additional branches are authorized by this implementation request. Existing
core Ditto behavior remains unchanged.

## Release UI trial preparation

The plugin now builds a genuine non-debuggable release variant for local group
UI evaluation; this does not enable the Ditto SDK or mesh runtime. Both debug
and release builds and their 95-test synthetic suites passed. The assembled
release APK is explicitly unsigned, not installable as a signed watch trial,
and must not be replaced by a re-signed debug build.

Core CI pins the established WearTAK release certificate SHA-256 to
`1579b600cd84e619e76a91c2db6b031938c999bc2aad926b9a51ec2b166d348d`.
Plugin signing-input and verification support uses CI references and the
canonical certificate pin, without obtaining or copying private key material.
Synthetic guard checks reject missing signing inputs or a missing explicit
verifier. The current worktree was rebuilt offline using the verified pinned
SDK AAR: 95 debug and 95 release tests passed. The release verifier correctly
reports the resulting non-debuggable APK as unsigned. A disposable synthetic
keystore was also used to sign that release APK; the real Gradle verifier
rejected the noncanonical certificate, and the synthetic key and signed test
artifact were removed. The unsigned APK's package is
`com.weartak.plugin.ditto`; it contains no Ditto runtime or mesh permissions.
Actual plugin-project signing variable availability, authorized CI signing,
installed host signer/grants, and watch behavior remain unverified. No watch was
connected through ADB during the readiness check, and no device installation
was attempted.

## Distribution and migration gates

1. **Ditto SDK terms:** the user confirmed that the applicable Agreement/Order
   Form covers distribution in the separate plugin APK. This is accepted as
   user confirmation, not independent review of the written agreement. The
   public [binary license](https://www.ditto.com/sdk-license) and
   [supplemental terms](https://www.ditto.com/legal/supplemental/license)
   remain relevant; preserve required notices and restrictions. Do not infer
   authorization to publish credentials or PDL, or to sublicense the SDK.
2. **Community identity:** the user directed reuse of the existing Ditto
   community identity and confirmed its authorization for
   `com.weartak.plugin.ditto`. This is user confirmation, not an independent
   verification of the identity or its scope. The release PDL is CI-provided
   credential material; its variable configuration is not verifiable from
   repository source. Configure provisioning without exposing the value. Never
   copy a PDL into source, docs, or artifacts.
   The [Android integration guide](https://docs.ditto.live/sdk/v4/install-guides/java/android)
   also requires the integrating app to declare and request its own mesh
   permissions; a separate plugin APK cannot rely on permissions declared only
   by the WearTAK core package.
3. **Behavioral parity:** keep the existing core integration until the new
   plugin is independently verified for identity provisioning, standalone
   operation, mesh transports, PLI/CoT/chat, point deletion and replay
   protection, power/BLE recovery, Companion handoff, settings, and rollback
   or migration. Migration contracts and host adapters remain default-off.
   Unregister acceptance is not a broker-retirement proof. The separate
   versioned retirement endpoint revokes preactivation registration, closes
   broker admission and waits boundedly for admitted work and policy observers.
   A timeout, lost response, repeated call or death is not proof. RETIRED does
   not attest Binder reply delivery, plugin-side draining or native shutdown.
   Promotion remains unavailable and plugin-side callback and reply draining
   must be verified independently.
   Migration preparation also refuses before bundled-engine quiescence: no
   production marker admission-and-drain proof exists. Before enabling it, close
   admission and persist admitted and coalesced edits before either permanent
   fence; retain closure through snapshot capture. Durable unsent network work
   must be preserved or transferred, not delivered to peers as a prerequisite.
   After a permanent or PREPARED fence, never reopen the legacy owner. Before
   fencing, retain it only when its unchanged sole ownership is proven; a
   failed persistence call does not prove rollback. Preparation also rejects
   active local ledger entries without point documents before fencing; their
   content cannot be reconstructed from ownership records alone.
   Marker drop, edit and clear requests now persist their desired state and
   ordered host intents before reporting local admission. While the legacy
   engine owns point writes, the service materializes and persists the exact
   outbound CoT, then dispatches eligible intents in per-UID order. A tracked
   Ditto callback reports success only after the native write and ledger flush;
   a missing, failed, or interrupted callback remains uncertain and is not
   replayed. Non-Ditto route acceptance is only a local offer, not peer delivery;
   deletion staging records a local durable tombstone, not native completion.
   At capacity, the journal compacts acknowledged history while retaining
   unresolved intents and per-UID anchors for UI order, effective state,
   deletion location and maximum materialized event time. Compacted historical
   acknowledgments are not a durable operation-receipt lookup; plugin receipt
   retention and cross-store reconciliation remain separate requirements.
   No plugin point dispatch is enabled after the ownership fence.
   The journal consumer can close admission and wait for its in-flight effects,
   including across consumer instances. This is not a global point-writer drain:
   the Ditto publication gate now counts EventBus submissions, direct publication
   and ledger retry work through completion of their native calls and callbacks.
   Timeout or a native exception cannot prove drain. Direct local ledger mutation,
   observer callbacks, persistent restart fencing and ordering across these gates
   still require a combined proof before cutover can use it.
   Known pre-native rejection remains retryable after its phase is persisted;
   attempted native writes and failed ledger commits remain uncertain.
   The local point mutation fence has a separate process-wide admission drain
   for ledger and watermark mutations, including a ledger flush's native call.
   It rejects new work while waiting and reports timeout without waiting on the
   writer monitor. The persistent fence commit runs on a tracked worker: a
   timeout or interruption leaves its outcome uncertain even if it later
   finishes. This is not a global barrier: journal and observer lifetime
   must still be coordinated, and the worker itself can remain blocked.
   Observer shutdown now has a bounded local wait for handle closure, entered
   callbacks and launched ingestion jobs. It is not connected to cutover;
   the SDK close documentation does not by itself prove that an already-invoked
   callback cannot enter the counter after that wait.
   The journal is not connected to the cutover barrier. Its local payload digest is not the
   unpublished point-receipt request digest. A closed journal alone does not
   drain service work, and recovery of a started dispatch does not prove its
   outcome. Do not enable migration or replay such work on this basis.
   Drop UI confirmation distinguishes a local journal save from delivery.

### Incumbent point encoding (not a receipt contract)

`CotMessageBuilder.marker` emits CoT 2.0 with the point UID and marker type,
`time=start` at snapshot construction, stale one year later, `how=h-g-i-g-o`,
`access=Undefined`, and point lat/lon/hae/ce/le. Its detail contains status
(readiness/battery), precisionlocation altsrc SRTM1, parent-device link
(production time, type `a-f-G-U-C`, callsign, relation `p-p`), color -1,
usericon iconsetpath, remarks and contact callsign. See `CotXml`: attributes
and content are raw, so receiver code must not assume automatic XML escaping.

`DittoService.onDittoDoc` asynchronously parses CoT and constructs the `mapitem`
record: `_id`=UID, `_r`=false, `_v`=2, `authorCs`/`e`=local callsign,
`AuthorId`/`d`=local device UUID, `authorType`/`w`=CoT type, `visible`/`f`=true,
`a`=event pks or local peer key, `b`/`n`=event time in milliseconds,
`c`=contact callsign, `g`=2.0, `h`=ce, `i`=hae, `j`=lat, `k`=le,
`l`=lon, `o`=stale milliseconds, `p`=how, `q`=access and `r`=detail XML.
`DittoPointLedger.publish` requires prior locally owned UID, validates owner
and peer, and adds `weartakPoint={version:1,owner,revision,deleted:false}`.
The revision is SHA-256 lowercase hex of the canonical reconstructed CoT
from `DittoCotDocument.decode`, not necessarily the original raw input XML.
Equal timestamps with unequal revisions conflict; older events are rejected.

`DittoPointLedger.delete` persists a terminal local ledger record. For a point
with a document it retains the document and revision, sets `_r=true`,
`visible=false`, `f=false`, and `weartakPoint.deleted=true` plus
`deletedAt=max(now, document.b)`; the local record becomes
`{uid,owner,deleted:true,document,pending:true,nativePhase:"NEVER_DISPATCHED"}`.
The phase becomes `DISPATCH_STARTED` before the native call and is removed
when the pending flag is durably cleared. An ownership-only unpublished
point has no document and no mesh tombstone. Ledger keys are SHA-256 of UID;
receive watermarks use SHA-256 of peer + NUL + UID, retaining owner, event time,
revision and deleted. Deletion is terminal and same-time conflicts are refused.
The independent TAK deletion CoT uses a distinct event UUID, type `t-x-d-d`,
one-day stale, link to target UID and `__forcedelete`. The journal consumer
derives its event UUID from the stable dispatch ID; the incumbent non-journal
builder defaults to a fresh UUID.

The data-only `PAYLOAD_POINT_RECORD_JSON_V1` DELETE profile wraps the actual
persisted tombstone document as `{"version":1,"document":{...}}`. It requires
the bound local peer, matching UID and owner, original event time and revision,
and the recorded deletion time; missing or inconsistent historical data is
rejected. It preserves compatible document fields without reconstructing the
overwritten prior lifecycle flags. The shared golden payload is
`app/src/test/resources/point-delete-v1.json` (excluding its file newline).
Serialization does not send an operation or prove its native outcome.
The `point-producer-drop.xml`, `point-producer-edit.xml`, and
`point-producer-delete.json` test resources contain synthetic bytes generated
through the incumbent marker builder, Ditto document materialization and
ledger deletion; fixture trailing newlines are not payload bytes. Their
historical peer is intentionally distinct from any new runtime peer. Site ID
alone does not prove peer-key continuity.
The plugin-to-host point endpoint is exposed only under the existing verified
plugin-only ownership profile and authenticated live transport session. Production
ownership proof remains unavailable; no host-to-plugin point sender is enabled.

The host journal's sequence, random dispatch ID and digest of its exact stored
payload identify a local intent only; they are not the future authenticated
point-receipt request digest. EventBus/network queue acceptance, the local
journal commit, ledger pending commit, native Ditto write and peer delivery are
distinct outcomes. `dispatch` can accept before `onDittoDoc` asynchronously
persists the ledger. The tracked callback occurs after `DittoPointLedger.flush`
writes the native document and persists pending=false; its prior persisted
native-start phase prevents automatic retry after an interrupted write.
Neither a local callback nor a staged deletion proves peer delivery or an
authenticated plugin receipt.

The plugin-repository checkout and approved branch are established. Final
identity provisioning, signing, migration, and host
and device compatibility remain coordination gates. No additional
branch, commit, push, or publication is authorized by repository creation or
SDK distribution confirmation.

## Focused existing behavior coverage

- `DittoServicePowerTest`, `DittoBleDiscoveryTest`,
  `DittoBleDiscoverySessionTest`, `DittoIdentityApplyTest`,
  `DittoReceiveIntegrationTest`, and the point-ledger/publication tests cover
  current Ditto lifecycle and data behavior.
- `PluginTransportHostV1ImplTest` covers typed chat, raw CoT ingress,
  plugin-origin echo prevention, outbound payload negotiation, delivery
  receipts, and session revocation.
