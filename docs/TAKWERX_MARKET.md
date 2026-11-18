# TAKwerx Market catalog and publishing

TAKwerx Market is a watch-only WearTAK plugin browser. It reads one curated
catalog selected at build time; it does not accept user-entered repositories.
Builds without catalog configuration show Market as unavailable and make no
catalog request.

The Plugins-menu icon is the TAKwerx Market ATAK plugin launcher icon, used
with permission: [source](https://github.com/takwerx/takwerx-market/blob/main/app/src/main/res/drawable/ic_launcher.png).
The source repository is licensed under AGPL-3.0.

## Configure a build

Provide both catalog values as Gradle properties or environment variables:

```powershell
.\gradlew.bat :app:assembleRelease `
  -PweartakMarketCatalogUrl=https://market.example.org/catalog.json `
  -PweartakMarketCatalogPublicKey=<BASE64_ED25519_X509_PUBLIC_KEY>
```

Equivalent environment variables are `WEARTAK_MARKET_CATALOG_URL` and
`WEARTAK_MARKET_CATALOG_PUBLIC_KEY`. The key is the Ed25519 **public** key in
X.509 DER form, Base64-encoded. Keep the matching private signing key in the
catalog publisher's protected signing environment; it must never be added to
this repository, a build argument, or an app build.

The configured catalog URL must be HTTPS, without user information or a
fragment. Catalog envelopes and APK URLs use HTTPS, verified platform TLS, and
no HTTPS-to-HTTP redirects. HTTPS redirects are supported for publisher release
hosts (including GitHub release assets). Catalog and APK downloads have
independent byte limits; APKs are capped at 100 MiB.

## Signed catalog format

The response is a JSON envelope. `payload` is Base64 of the exact UTF-8 bytes
of the catalog JSON; `signature` is Base64 of an Ed25519 signature over those
bytes. The configured public key verifies the envelope before any entries are
parsed.

```json
{
  "payload": "<base64 of catalog JSON>",
  "signature": "<base64 Ed25519 signature>"
}
```

The signed payload has `schemaVersion: 1` and a `plugins` array:

```json
{
  "schemaVersion": 1,
  "plugins": [
    {
      "id": "weather",
      "name": "Field Weather",
      "description": "On-watch weather information.",
      "publisher": "Example Maintainers",
      "publisherType": "community",
      "packageName": "org.example.weartak.weather",
      "versionName": "1.2.0",
      "versionCode": 12,
      "minHostVersionCode": 5840000,
      "maxHostVersionCode": null,
      "apkUrl": "https://github.com/example/weather/releases/download/v1.2.0/weather.apk",
      "apkSha256": "<64 lowercase hexadecimal characters>",
      "signerSha256": "<SHA-256 of APK signing certificate DER>",
      "sourceUrl": "https://github.com/example/weather",
      "hostUpdate": false
    }
  ]
}
```

`publisherType` is `official` or `community`. `versionCode` and host version
bounds are positive Android version codes. Set the optional maximum host code
to `null` for no upper bound. `sourceUrl` is optional and, when present, must
be HTTPS.

## Publisher and curation flow

Plugin maintainers publish their signed plugin APK and source on their own
release host. The WearTAK catalog maintainer reviews the plugin and its
publisher, then adds a catalog entry pointing directly at the release APK.
First-party and approved community plugins use the same release path; their
publisher type is explicitly identified. An entry cannot be added by a wearer
or discovered from arbitrary repositories.

Before handing an APK to Android's user-confirmed package installer, WearTAK
checks the download size and SHA-256, package name, version code, and signing
certificate digest against the signed entry. Updates must also match the
installed package signer. Host version compatibility is checked before
download and again before installation. Only an official `hostUpdate` entry
for WearTAK's exact application ID may target the host package. If Google Play
is the host installer, host sideload updates are blocked; inability to verify
the host install source also blocks them.

Android handles unknown-source approval and final install confirmation. After
package replacement, WearTAK's existing package-change handling refreshes
plugin discovery and bindings without changing the plugin's enabled state.

Catalog signing, publisher review, release hosting, and catalog deployment are
operational responsibilities outside this client repository. No production
catalog URL or signing key is checked in by default.
