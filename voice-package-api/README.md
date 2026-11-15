# Voice package Binder API V1 (development)

Shared by WearTAK host, the SDK Voice plugin and standalone Voice test app. The
canonical source is the voice-package-api directory in alexanderhavang-ops/weartak-voice.
The host carries an identical source copy for a reproducible development build.
This is an optional prototype extension; it does not modify frozen upstream SDK
parcels or transactions. See the architecture/API proposal in the containing project.
Pending payloads use bounded byte arrays and one-use host memory handles; never
host credentials, unprotected broadcasts or temporary file grants.
