# Enrollment from chat

With Commands enabled, any allowlisted callsign can send enrollment commands on
any supported channel: direct/group/broadcast TAK chat, multicast, Ditto, SIT(x),
or the paired Companion bridge. Callsign allowlisting is **not authentication**.
The existing original-message timestamp (five-minute window), sender/message-ID
replay checks and network/phone-management locks still apply. An MDM
`tak_server_list`, even an empty list, is authoritative and prevents chat changes.

```
#weartakcommand enrollserver Ops, tak.example.invalid, 8089, operator, example-only
```

The joined prefix (`#weartakcommandenrollserver`) also works. Split only the first
four commas: everything after the fourth comma is the password, including commas,
quotes, equals signs and internal spaces. Surrounding whitespace is trimmed.
The first three fields are mandatory. Omit both credentials to prompt on the
watch; supplying only one is rejected. The existing
`name=Ops url=tak.example.invalid [port=8089] [username=operator password="..."]`
syntax remains supported with its original quoted-value escaping. Commands are
bounded to 768 characters; legacy individual values are bounded to 128 characters.

For key/value arguments, use double quotes around values containing spaces.
Inside quotes, `\\` represents one literal backslash and `\"` one literal quote;
`!` and `_` need no escaping. Unquoted backslashes are literal. Quoted passwords
retain leading and trailing spaces; CSV fields still trim surrounding whitespace.
For example (synthetic credentials only):

```
#weartakcommandenrollserver name="TAK Ops" url=tak.example.invalid port=8089 username=operator-wt password="example_\\only!"
```

A normalized name (trimmed, case-insensitive) **or** normalized address/port match
updates that server in place, preserving its server ID and enable setting and
replacing its enrollment credentials. If the name and endpoint identify different
records, nothing changes. New servers are enabled and registration starts
immediately through the same encrypted storage and certificate flow as the UI.
Existing transport/master-enable, connectivity and companion gates still apply;
this command does not override a disabled transport or managed policy.

Updates preserve certificate mode. For `isP12Cert` servers, the existing P12 and
its password are retained; supplied username/password are saved but do not switch
the server out of certificate authentication. For enrollment-mode servers, cached
P12 fields are cleared so normal registration can refresh the certificate. This
matches the certificate handling in `WearTAKSettingsActivity.kt`'s
`edit_server_screen` confirmation (`changedTakServerDetail`): the P12 branch
preserves certificate fields, while the enrollment branch explicitly clears
`p12Cert` and `p12CertPassword`. The UI hides username/password fields in P12 mode;
chat still saves the explicitly supplied credentials without discarding the P12.

Replies are `Server <name> added` or `Server <name> updated`, confirming durable
storage, **not** successful certificate enrollment or connection. Failures use
credential-free reasons. The credential-free path posts the existing on-watch
prompt instead. Credentials are never placed in that prompt or its intent.

Registration failure logs distinguish the save and connect phases and retain a bounded
exception-type cause chain. Connect setup also records an allowlisted `registrationStep`:
`SERVICE_INSTANCE`, `PERSISTED_LIST_RELOAD`, `ID_NORMALIZATION`, `RUNTIME_RECONCILIATION`,
`DETAIL_LOOKUP`, or `CONNECTION_DISPATCH`. Nested setup failures retain the innermost step;
these markers distinguish local setup from asynchronous enrollment/transport failures.
Registration uses the strict persisted-list reader so a decrypt/read failure keeps its original
cause instead of becoming a generic unavailable-list exception.
Structured enrollment failures preserve their stage, category,
and HTTP status even when wrapped by a setup exception. These logs do not include exception
messages, throwable stack traces, server identifiers/addresses, credentials, or certificate/key
contents. Registration still terminates explicitly on failure; TLS and certificate checks are unchanged.

## Secret handling and limits

Enrollment commands, including disabled, malformed and refused requests, are
excluded from the watch inbox, outgoing chat/mesh queues and plugin forwarding.
Cleanup runs after command processing (including failure/cancellation); local
Ditto copies are evicted after processing and on initial/repeated snapshots.
Payload logging, payload-derived ingress/replay fingerprints and secret-bearing
runtime stringification are suppressed. Notifications containing enrollment
command text are canceled at startup and during cleanup. Replay metadata does
not contain command text or credential-derived hashes. The shared command replay
cache remains process-local (128 pairs), not a durable cross-restart execution
ledger. Chat history is in memory in this version; Ditto has its own persistent
database. App diagnostics/crash journals retain only fixed event/metric data.

**Deletion is best effort, not secure erasure.** Ditto may persist or relay a
document before the application observer can evict it. Evicted documents may
return from peers and are cleaned again; other replicas, SDK database/WAL/flash
remnants and in-memory JVM strings may persist. The watch cannot delete sender
ATAK history, TAK/SIT(x) server logs, other devices' notifications, backups or
captures. Use short-lived enrollment passwords and rotate them afterward.

Stored credentials intentionally remain available through the existing secure
server settings and Companion view/edit/settings-sync protocol. That protocol is
unchanged; its phone-side copies are outside command-message purge. Credentials
are not included in diagnostic exports. Enrollment retains normal HTTPS trust
and hostname validation; only use a server address you trust.

## Private CA trust import (watch, no file picker)

CA import is optional, under the collapsed **Advanced** section in the normal
**New Server / Edit Server** form. Public CAs (such as Let's Encrypt) use normal
system trust without importing anything. An untrusted-issuer TLS failure offers
**Import CA...**, opening the same staged-certificate review flow for that saved
server. Other TLS errors (such as an expired certificate or hostname mismatch),
authentication failures and network errors do not offer CA import.
It does not create a special server type: the same server list, status,
enable/disable, edit, registration and delete flows apply. Each watch enrolls
its own client certificate using its enrollment credentials. Importing a CA
does not import a client identity or private key.

1. Obtain one public X.509 CA certificate in PEM or DER format from your
   administrator, and its SHA-256 fingerprint over a separate trusted channel.
   Bundles, PKCS#12 truststores, private keys, expired certificates and non-CA
   certificates are not supported. Maximum size: 64 KiB.
2. With authorized ADB access to the watch, stage the public certificate in the
   app's external files directory. For regular WearTAK:

   ```powershell
   adb -s WATCH_SERIAL shell mkdir -p /sdcard/Android/data/com.aitech.weartak_civ/files/trust
   adb -s WATCH_SERIAL push .\server-ca.pem /sdcard/Android/data/com.aitech.weartak_civ/files/trust/ca.crt
   ```

   WearTAK Dev uses `com.aitech.weartak_civ.dev` instead. ADB must already be
   authorized; Knox policy may prohibit ADB or external app-file access. Such
   devices need an administrator-approved staging mechanism. No file-picker,
   Companion protocol change or automatic download/trust is required.
3. Open **New Server** or **Edit Server**, enter the actual server address and
   streaming port, then open **Advanced > Review staged CA**. Read the subject, expiration,
   endpoint and full SHA-256 fingerprint. Compare every fingerprint byte with
   your administrator, then tap **Approve this CA** and confirm/save the form.
   Review/approval alone does not save anything; canceling the form discards it.
4. The approved certificate is copied into Android-Keystore-backed encrypted
   app storage, bound to that server ID, normalized host and streaming port.
   It is not included in Companion settings exports or phone snapshots.
   Enrollment on 8446 accepts either normal Android platform trust or this
   approved CA for this server. Approval does not replace public CA trust.
   Streaming on the configured port (normally 8089) and REST APIs on 8443 keep
   their existing approved-CA behavior; no global CA or trust-all fallback is added.
   Redirects are disabled for enrollment and APIs using an approved CA.
5. **Edit Server > Advanced > Remove approved CA on save**, then save, revokes the approval
   and reconnects using existing defaults. Deleting the server or changing its
   endpoint removes its approval; changing the endpoint back requires review
   again. Replacing/removing trust invalidates that connection and its cached
   enrollment certificate. The staged file is never automatically trusted or
   reread; remove it with authorized ADB after import if desired.

Hostname and IP verification stay strict for imported trust on enrollment,
streaming and APIs. If connecting to an IP address, the server certificate must
contain that exact IP in a Subject Alternative Name (SAN). Otherwise use a
resolvable DNS name present in the certificate. Importing a CA does not fix a
wrong hostname, firewall, disabled direct transport, phone handoff or managed
configuration lock. Existing non-imported server behavior is unchanged.

For ODIN-TAKX-SERVER, use `10.0.0.120`, streaming `8089`, enrollment `8446`,
provided its server certificate has an IP SAN for `10.0.0.120`; otherwise use
its matching, resolvable DNS SAN. Never bypass verification to connect.
