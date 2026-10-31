# WearTAK Host Services SDK 1.0.0

Optional **extension protocol version 1**, independent of the pinned WearTAK plugin
SDK lifecycle APIs 1, 2 and 3. Keep your existing `getApiVersion()`. Add this AAR
alongside the existing SDK; it does not duplicate or replace existing SDK classes.
No new permissions, certificate trust exceptions, or plugin signature rules.
The library supports Android API 30+ and emits Kotlin 2.0 metadata, compatible
with consumers on Kotlin 2.0.21; the host app's Kotlin 2.2 compiler is not required.

Build: `gradlew.bat :weartak-plugin-host-services-sdk:assembleRelease`.
Output: `build\outputs\aar\weartak-plugin-host-services-sdk-release.aar` inside this module.
Canonical source is `https://git.tak.gov/core/weartak-core/wearos-tak-civ`;
the normal main pipeline mirrors it to `https://github.com/aegorsuch/weartak-wearos`.
Pin a full source commit SHA,
not a moving branch. The `android_mopp_check` MR job publishes the AAR for integration;
`main_release` publishes it alongside `verified-sdk.sha256` and the signed host APK.

For consumer CI, this module also builds standalone without configuring the host
application or requiring its signing configuration:

```powershell
.\gradlew.bat -p weartak-plugin-host-services-sdk assembleRelease
```

Linux CI: `./gradlew -p weartak-plugin-host-services-sdk assembleRelease`.
Use JDK 17, Android SDK 35 and the checked-in Gradle wrapper/catalog from that
same pinned source commit. The resulting Kotlin metadata targets 2.0.

## Discovery

During either `initializePluginV2` or `initializePluginV3`:

```kotlin
import com.weartak.plugin.sdk.host.v1.HostServicesContract as C

val services = C.fromHostExtras(init.hostExtras)
// null means unsupported; disable these actions on older hosts.
```

`PluginInitParcel.hostExtras` carries:

| Key | Type | Value |
|---|---|---|
| `com.weartak.plugin.host.services.version` | Int | `1` |
| `com.weartak.plugin.host.services.v1` | IBinder | `IWearTakHostServices` |

Use the Binder only for this initialized plugin connection. Unload/rebind revokes
the old instance. Each method checks the admitted plugin UID; forwarding the Binder
to another UID does not grant access.

All three AIDL methods return a Bundle:
`getCapabilities()`, `requestMapAction(Bundle)`, `requestTakServer(Bundle)`.
Calls are synchronous. **Call from a plugin worker thread, never the UI thread**.
No automatic retries: REST calls may have side effects.

Every response has `status: String` and `message: String`. Status values:
`ok`, `invalid_request`, `unauthorized`, `closed`, `busy`, `unavailable`,
`not_found`, `network_error`, `response_too_large`.
`ok` for HTTP means a complete HTTP response, **not** HTTP success; inspect
`httpStatus`, including 3xx, 4xx and 5xx. Messages are diagnostics, not stable codes.

`getCapabilities()` additionally returns:

| Key | Type | Meaning |
|---|---|---|
| `version` | Int | `1` |
| `mapActions` | ArrayList<String> | `bloodhound`, `target` |
| `takHttps` | Boolean | Bridge implemented |
| `servers` | ArrayList<Bundle> | Currently connected eligible TAK servers |

Each server Bundle has `serverId: String` (opaque host identity) and `name: String`
(display name). Refresh capabilities after connection changes. No credentials,
server address, client certificate or private key are returned.

## Map actions

```kotlin
val result = services?.requestMapAction(Bundle().apply {
    putString(C.KEY_ACTION, C.ACTION_BLOODHOUND) // or ACTION_TARGET
    putString(C.KEY_UID, incident.id)
    putString(C.KEY_LABEL, incident.name)
    putDouble(C.KEY_LATITUDE, incident.latitude)
    putDouble(C.KEY_LONGITUDE, incident.longitude)
})
```

Request fields:

| Key | Type | Requirement |
|---|---|---|
| `action` | String | `bloodhound` or `target` |
| `uid` | String | Optional nonblank, max 256 characters |
| `label` | String | Optional, max 256 characters |
| `latitude` | Double | Optional finite [-90, 90] |
| `longitude` | Double | Optional finite [-180, 180] |

Provide both coordinates or neither. Coordinates take precedence; if omitted,
`uid` must resolve to a known host contact, point or Bloodhound target.
For incident coordinates, the returned `uid` is
`plugin:<plugin-package>:<supplied-uid>` (random suffix if UID omitted).
This prevents collisions with host assignments and other plugins.
UID-only contacts retain existing source-aware moving-contact Bloodhound behavior.

`bloodhound` reuses the host target repository and activation request pipeline;
`ok` means activation **requested**, not a completion acknowledgement.
If the Bloodhound service is stopped, returns `unavailable`, with no new target.
`target` launches/reuses the host map's existing point-focus path, snapping/zooming
to that location. Android foreground restrictions can still govern activity display.
Both methods return the resolved `uid` on `ok`.

Hosts supporting incident toggles advertise `bloodhound.stop` in `mapActions`.
On those hosts, `getCapabilities()` includes optional String `activeBloodhoundUid`,
the original supplied UID with the calling plugin's `plugin:<package>:` prefix removed,
only when that plugin owns the active target. Other plugins' targets and host contacts
are not exposed. Refresh capabilities on resume/schema refresh; active tracking can change elsewhere.

To stop, send only `{action: "bloodhound.stop", uid: "<original-incident-uid>"}`.
Stopping requires that exact plugin-owned target to be currently active; otherwise returns
`not_found`, without stopping another incident, contact, or plugin target. Coordinates and
labels are forbidden on stop. Successful stop retains the target record, updates complications,
and does not navigate away. It does not alter CAD responding/assignment status.

Bloodhound start may additionally send Boolean `returnToMain = true` to launch/reuse
WearTAK's main screen after activation is requested. Default false preserves existing callers.
Gate this field on the same `bloodhound.stop` capability: old hosts reject unknown request keys.
Only Bloodhound start accepts it; target and stop do not. Invalid locations, unknown targets,
or a stopped Bloodhound service do not navigate away. An Android activity-launch failure returns
an explicit failure; activation already requested is not silently undone.

No vehicle identity, vehicle selection or incident-prioritization capability is added.
Existing ACTION cards can dispatch
to plugin code, which calls this extension. Do not treat watch UID/callsign as a
CAD vehicle identifier.

The current host's generic hosted ACTION dispatcher accepts plugin-defined enabled
actions, including `takcad.incidents.refresh`, `takcad.server.next`,
`takcad.bloodhound` and `takcad.target`; `incidentId` is a string argument inside
`actionArgsJson`. These are **plugin UI action IDs**, not map bridge action values.
The plugin resolves that incident and invokes the bridge with coordinates/UID.
Matching presentation-capable hosts render SDK-enumerated icons (including BLOODHOUND and STOP),
not arbitrary plugin-provided assets. Retain accessible action labels. This presentation capability
is separately advertised through the core SDK's `PluginInitParcel.hostExtras`.

## Authenticated TAK plugin REST

```kotlin
val result = services?.requestTakServer(Bundle().apply {
    putString(C.KEY_SERVER_ID, selectedHostServerId) // optional with exactly one connected server
    putString(C.KEY_METHOD, "GET")
    putString(C.KEY_PATH,
        "/Marti/api/plugins/tak.server.plugins.TakCadServerPlugin/submit")
    putString(C.KEY_QUERY, "fn=getIncidents")
})
if (result?.getString(C.KEY_STATUS) == C.OK) {
    val httpStatus = result.getInt(C.KEY_HTTP_STATUS)
    val body = result.getString(C.KEY_BODY)
}
```

| Key | Type | Requirement |
|---|---|---|
| `serverId` | String | Optional opaque connected server ID; required when multiple connected |
| `method` | String | `GET` (default), `POST`, `PUT`, `PATCH`, `DELETE` |
| `path` | String | Required canonical path below `/Marti/api/plugins/`, max 2048 characters |
| `query` | String | Optional encoded query, no leading `?`, max 8192 characters |
| `body` | String | Optional UTF-8 text, max 32768 bytes; forbidden for GET |
| `contentType` | String | `application/json` (default), `application/x-www-form-urlencoded`, `text/plain` |

Use query encoding for each parameter value, e.g. form/URL encoding; do not
concatenate arbitrary incident/user text into the query. No arbitrary headers,
absolute URLs, userinfo, fragments, encoded path segments, dot traversal, or port
overrides. Unknown fields and wrong value types are rejected.

All requests send `Accept: application/json`, independently of the request body's
`contentType`, so TAK Server plugin endpoints can negotiate JSON responses.

The host builds `https://<selected connected server>:8443<path>?<query>`, uses
that connection generation's TLS client identity and trust manager, and applies
strict hostname validation. No trust-all or hostname bypass. A TAK server certificate
not valid for its configured HTTPS hostname fails closed, even if another host
REST feature supports legacy certificate fallback.

Requests follow the existing TAK connection network route; disconnect/replacement
cancels session work and discards stale results. Plugin unload cancels its own
in-flight calls and revokes the Binder. Redirects and transport retries are disabled.
One HTTP call per plugin, four globally; excess calls return `busy`.
Connect timeout 10s, read timeout 15s, overall call timeout 20s.
Response body limit 65536 **decompressed bytes**, UTF-8; oversize responses return
`response_too_large` without truncated success. No response headers/cookies/keys
are exposed. Successful bridge results contain `serverId: String`,
`httpStatus: Int`, and `body: String`.

This bridge grants admitted plugins authenticated REST access under the constrained
plugin path, including writes. It does not impose TAK CAD-specific authorization;
the TAK server still authorizes each REST operation. Do not automatically replay
mutations after errors: a server may have applied a write before its response was lost.
