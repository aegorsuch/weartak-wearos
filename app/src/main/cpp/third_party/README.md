# WearVPN third-party source snapshots

The directories in this folder are vendored source snapshots used to build
`libwearvpn.so`. Each snapshot is pinned to the immutable upstream Git commit
listed below. They are source copies, not nested Git repositories.

| Dependency | Upstream repository | Pinned commit | License | License retained at |
| --- | --- | --- | --- | --- |
| Asio | <https://github.com/chriskohlhoff/asio.git> | `bd500f0a018db9a845ebaaed5c0318343ae9f497` | BSL-1.0 | `asio/LICENSE_1_0.txt` |
| fmt | <https://github.com/fmtlib/fmt.git> | `6dac6cad052d6593f5fa07529d912f3bd0d6bb11` | MIT | `fmt/LICENSE` |
| mbed TLS | <https://github.com/Mbed-TLS/mbedtls.git> | `068ff080b369adfac81509f9b57b2afabaf82dc5` | Apache-2.0 OR GPL-2.0-or-later | `mbedtls/LICENSE` |
| mbed TLS framework (nested) | <https://github.com/Mbed-TLS/mbedtls-framework> | `dde0c4a0e448a0552f18817dcea633bb851fd288` | Apache-2.0 OR GPL-2.0-or-later | `mbedtls/framework/LICENSE` |
| lwIP | <https://git.savannah.nongnu.org/git/lwip.git> | `71106551813c2e2da36209d39faa406b64ae5ab8` | BSD-3-Clause | `lwip/COPYING` |
| LZ4 | <https://github.com/lz4/lz4.git> | `0774d05537f9762f838f7ab541b7765f1a729cb5` | BSD-2-Clause for compiled `lib/`; GPL-2.0-or-later for other snapshot files | `lz4/lib/LICENSE`, `lz4/LICENSE` |
| OpenVPN 3 | <https://github.com/OpenVPN/openvpn3.git> | `3ae0f347b2033e1a71a2f11a5a01c19748ac2d86` | AGPL-3.0-only OR MPL-2.0; embedded HTTP component BSL-1.0 | `openvpn3/LICENSE.md`, `openvpn3/LICENSES/`, `openvpn3/openvpn/http/LICENSE_1_0.txt` |

Gradle packages this dependency table and the listed license texts under
`assets/licenses/native/` in each app build. OpenVPN 3's separate OpenSSL
exception is retained in source; this build links mbed TLS, not OpenSSL.

This inventory records upstream license declarations; it is not legal
clearance. The applicable OpenVPN 3 license choice and compatibility with the
WearTAK app's distribution remain unresolved and require legal review before
release.

## Build and tests

The app's Gradle build compiles `libwearvpn.so` with the pinned Android NDK
and CMake versions. Linux hosts can run the no-VPN native tests with:

```sh
cmake -S app/src/main/cpp -B app/build/native-tests
cmake --build app/build/native-tests
ctest --test-dir app/build/native-tests --output-on-failure
```

These tests cover profile shape, stale handles, idempotent stopped-state calls,
concurrent lifecycle access, and independent partial-read buffers. They do not
replace two-stream tunnel verification on the target watch.

## Reproducing or refreshing a snapshot

Fetch the repository in the table into a temporary location, check out the
listed commit in detached-HEAD mode, and copy its tracked files into the
matching directory here. Do not copy the temporary `.git` directory. Preserve
the upstream license file at the path listed in the table.

For mbed TLS, initialize its `framework` submodule recursively at the revision
recorded above before copying the snapshot. Copy the framework source but do
not copy its nested `.git` metadata.

Do not update a snapshot to a branch tip or a newer release as part of a normal
build. A dependency update must deliberately change the full commit ID in this
document and be reviewed with the source changes.

Snapshots may be pruned to compiled sources, transitive includes, required
CMake/build inputs, and license materials. Keep source files listed by
vendored CMake projects even when a target ABI does not compile them: CMake
checks those paths during configuration. Reconfigure every supported ABI after
pruning or dependency changes, and retain every listed license file.
