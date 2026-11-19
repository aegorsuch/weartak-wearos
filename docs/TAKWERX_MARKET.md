# TAKwerx Market catalog and publishing

TAKwerx Market is a watch-only WearTAK plugin browser. It reads one curated
catalog selected at build time; it does not accept user-entered repositories.
Builds without catalog configuration show Market as unavailable and make no
catalog request.

WearTAK checks the signed catalog at process startup and when Android reports
validated internet connectivity. A transient transport failure gets one retry
while connected. Returning to the WearTAK home activity also retries an
interrupted check when online. WearTAK compares only compatible releases already
present in that catalog with installed package versions and signers. The yellow
home warning opens the Market for review; it is not a startup dialog. APK
verification and Android's installer confirmation remain required.
Opening Market with updates available hides the warning for the current home
activity session. When WearTAK Home starts again, the warning returns if
eligible updates remain.
Market rows show the release name and publisher, with a check for current
installs or a download action for eligible installs and updates. Blocked
releases show why they cannot be installed.

The client does not scan GitHub or discover repositories. Publisher code and
approvals are maintained in the
[canonical TAK.gov Market project](https://git.tak.gov/weartak-plugins/weartak-takwerx-market)
and mirrored to [GitHub](https://github.com/aegorsuch/weartak-takwerx-market).
The WearTAK GitHub mirror calls the standalone publisher at a reviewed commit
SHA. It checks only repositories in
[`approved-repositories.json`](https://github.com/aegorsuch/weartak-takwerx-market/blob/main/market-publisher/approved-repositories.json)
and publishes the signed catalog to the existing WearTAK Pages site hourly.
The initial allowlist contains WearTAK and seven personal `weartak-plugin-*` repositories with
verified stable APKs; SDK and template repositories are excluded. Six other
personal plugin repositories are listed as pending until they publish stable
APKs and their package/signer metadata is reviewed. GitHub's latest-release
API omits drafts and pre-releases, so evaluation and development releases are
not offered.

The publisher inspects each selected APK's package, version, and signing
certificate, computes its SHA-256, and signs the catalog with Ed25519. New
repositories require a maintainer-reviewed change to the allowlist. Do not add
a repository based only on its name or source link. Community plugin entries
are limited to WearTAK host version code 5840000 and newer; revise their
compatibility bounds when release metadata has been reviewed for another host
range. Unchanged release metadata is reused only from the previously
signature-verified catalog, avoiding repeated APK downloads during hourly
checks.

Only the WearTAK mirror deploys the catalog; the standalone workflow does
not deploy on its own. When adopting a publisher change, review the mirrored
commit and update the reusable workflow reference, `publisher_ref`, the
pull-request test checkout, and the mirror's Actions allowlist to that same
SHA. Only trusted `main` publishing runs receive the signing key.

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
independent byte limits; APKs are capped at 250 MiB.

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

## Configure the publisher

Create an Ed25519 key pair outside the repository. Add the PEM private key as
the GitHub repository secret `MARKET_CATALOG_SIGNING_KEY` and the Base64
X.509 DER public key as repository variable `MARKET_CATALOG_PUBLIC_KEY`. The
publisher verifies that the two keys match before publishing. Never commit or
log the private key. From a trusted local shell with OpenSSL:

```text
openssl genpkey -algorithm ED25519 -out market-signing-private.pem
openssl pkey -in market-signing-private.pem -pubout -outform DER -out market-signing-public.der
openssl base64 -A -in market-signing-public.der
```

Paste the PEM private key into
[repository Actions secrets](https://github.com/aegorsuch/weartak-wearos/settings/secrets/actions)
and the final command's output into
[repository Actions variables](https://github.com/aegorsuch/weartak-wearos/settings/variables/actions)
as `MARKET_CATALOG_PUBLIC_KEY`. See GitHub's [encrypted secrets
guide](https://docs.github.com/actions/security-guides/using-secrets-in-github-actions)
and [repository variables guide](https://docs.github.com/actions/learn-github-actions/variables).

The catalog URL is:

```text
https://aegorsuch.github.io/weartak-wearos/catalog.json
```

Configure GitHub Pages on the WearTAK mirror to deploy from GitHub Actions.
Do not rotate the signing key or change the Pages URL just to update the
publisher: deployed watches already trust that URL and key. Set
`WEARTAK_MARKET_CATALOG_URL` and `WEARTAK_MARKET_CATALOG_PUBLIC_KEY` in the
TAK.gov release-build environment to the URL and same public key. These are
public values; the private key stays in the GitHub Actions secret. The existing
Gradle build also accepts the corresponding `weartakMarketCatalogUrl` and
`weartakMarketCatalogPublicKey` properties. Until these values are configured,
Market remains unavailable and makes no catalog request.

## Publisher and curation flow

Plugin maintainers publish their signed plugin APK and source on their own
GitHub release. The WearTAK catalog maintainer reviews the repository, APK
package, release asset pattern, and compatibility range before adding an
allowlist entry, including package name, signer digest, asset pattern, and
host compatibility range. The hourly publisher selects the latest stable APK
from that approved repository. First-party and approved community plugins use
the same release path; their publisher type is explicitly identified. An
entry cannot be added by a wearer or discovered from arbitrary repositories.

APK assets must fit WearTAK's 250 MiB download limit. The current WearTAK
release APK is about 204 MiB, leaving roughly 46 MiB of headroom. This ceiling
accommodates the host APK while features move into plugins over time; APK
downloads remain bounded, checksummed, and user-confirmed.

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

The publisher never installs APKs. WearTAK still verifies the signed catalog,
APK checksum, signer, package, and host compatibility, then requires Android's
user-confirmed installer flow.
