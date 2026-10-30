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
