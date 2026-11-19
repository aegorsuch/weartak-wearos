import assert from "node:assert/strict";
import { generateKeyPairSync, verify } from "node:crypto";
import test from "node:test";
import {
  fetchPreviousCatalog,
  buildCatalog,
  MAX_APK_BYTES,
  parseSignerSha256,
  signCatalog,
} from "../publish_catalog.mjs";

function source(overrides = {}) {
  return {
    owner: "aegorsuch",
    repo: "weartak-plugin-example",
    id: "example",
    name: "Example",
    description: "Example WearTAK plugin.",
    publisher: "aegorsuch",
    publisherType: "community",
    hostUpdate: false,
    packageName: "org.example.plugin",
    expectedSignerSha256: "a".repeat(64),
    minHostVersionCode: 5840000,
    maxHostVersionCode: null,
    assetPattern: "^WearTAK-Plugin-Example-.*\\.apk$",
    ...overrides,
  };
}

function fetchForRelease(assets, { releaseOverrides = {}, assetBytes = new Map() } = {}) {
  return async (url) => {
    if (url.toString().startsWith("https://api.github.com/")) {
      return new Response(JSON.stringify({
        draft: false,
        prerelease: false,
        assets,
        ...releaseOverrides,
      }), { status: 200 });
    }
    const bytes = assetBytes.get(new URL(url).pathname.split("/").pop());
    if (!bytes) return new Response(null, { status: 404 });
    return new Response(bytes, { status: 200 });
  };
}

test("builds entries only from stable APK assets and uses verified metadata", async () => {
  const assets = [
    {
      name: "WearTAK-Plugin-Example-1.apk",
      size: 3,
      browser_download_url: "https://github.com/aegorsuch/weartak-plugin-example/releases/download/v1/WearTAK-Plugin-Example-1.apk",
    },
    {
      name: "provenance.json",
      size: 3,
      browser_download_url: "https://github.com/aegorsuch/weartak-plugin-example/releases/download/v1/provenance.json",
    },
  ];
  const apk = Buffer.from([1, 2, 3]);
  const catalog = await buildCatalog(
    { schemaVersion: 1, repositories: [source()] },
    {
      fetchImpl: fetchForRelease(assets, {
        assetBytes: new Map([[assets[0].name, apk]]),
      }),
      inspect: async () => ({
        packageName: "org.example.plugin",
        versionName: "1.2.0",
        versionCode: 12,
        signerSha256: "a".repeat(64),
      }),
    },
  );

  assert.equal(catalog.plugins.length, 1);
  const { publisherReleaseFingerprint, ...plugin } = catalog.plugins[0];
  assert.match(publisherReleaseFingerprint, /^[0-9a-f]{64}$/);
  assert.deepEqual(plugin, {
    id: "example",
    name: "Example",
    description: "Example WearTAK plugin.",
    publisher: "aegorsuch",
    publisherType: "community",
    packageName: "org.example.plugin",
    versionName: "1.2.0",
    versionCode: 12,
    minHostVersionCode: 5840000,
    maxHostVersionCode: null,
    apkUrl: assets[0].browser_download_url,
    apkSha256: "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
    signerSha256: "a".repeat(64),
    sourceUrl: "https://github.com/aegorsuch/weartak-plugin-example",
    hostUpdate: false,
  });
});

test("rejects releases signed by a certificate outside the approved profile", async () => {
  const asset = {
    name: "WearTAK-Plugin-Example-1.apk",
    size: 3,
    browser_download_url: "https://github.com/aegorsuch/weartak-plugin-example/releases/download/v1/WearTAK-Plugin-Example-1.apk",
  };
  await assert.rejects(
    buildCatalog(
      { schemaVersion: 1, repositories: [source()] },
      {
        fetchImpl: fetchForRelease([asset], {
          assetBytes: new Map([[asset.name, Buffer.from("apk")]]),
        }),
        inspect: async () => ({
          packageName: "org.example.plugin",
          versionName: "1.2.0",
          versionCode: 12,
          signerSha256: "b".repeat(64),
        }),
      },
    ),
    /APK signer mismatch/,
  );
});

test("reuses unchanged approved release metadata without downloading APK again", async () => {
  const assets = [{
    id: 17,
    name: "WearTAK-Plugin-Example-1.apk",
    size: 3,
    updated_at: "2026-01-01T00:00:00Z",
    browser_download_url: "https://github.com/aegorsuch/weartak-plugin-example/releases/download/v1/WearTAK-Plugin-Example-1.apk",
  }];
  const apk = Buffer.from([1, 2, 3]);
  const fetchImpl = fetchForRelease(assets, {
    assetBytes: new Map([[assets[0].name, apk]]),
  });
  const firstCatalog = await buildCatalog(
    { schemaVersion: 1, repositories: [source()] },
    {
      fetchImpl,
      inspect: async () => ({
        packageName: "org.example.plugin",
        versionName: "1.2.0",
        versionCode: 12,
        signerSha256: "a".repeat(64),
      }),
    },
  );
  const secondCatalog = await buildCatalog(
    { schemaVersion: 1, repositories: [source()] },
    {
      fetchImpl,
      previousCatalog: firstCatalog,
      inspect: async () => assert.fail("Unchanged release must reuse signed catalog metadata."),
    },
  );
  assert.deepEqual(secondCatalog, firstCatalog);
});

test("skips prereleases and fails when an approved repository has no stable release", async () => {
  const prereleaseCatalog = await buildCatalog(
    { schemaVersion: 1, repositories: [source()] },
    {
      fetchImpl: fetchForRelease([], { releaseOverrides: { prerelease: true } }),
      inspect: async () => assert.fail("Prerelease must not be inspected."),
    },
  );
  assert.deepEqual(prereleaseCatalog.plugins, []);

  await assert.rejects(
    buildCatalog(
      { schemaVersion: 1, repositories: [source()] },
      { fetchImpl: async () => new Response(null, { status: 404 }) },
    ),
    /has no stable release/,
  );
});

test("does not fetch repositories listed only as pending", async () => {
  const catalog = await buildCatalog({
    schemaVersion: 1,
    repositories: [],
    pendingRepositories: ["example-owner/weartak-plugin-not-listed"],
  }, {
    fetchImpl: async () => assert.fail("Pending repository must not be queried."),
  });
  assert.deepEqual(catalog.plugins, []);
});

test("skips APKs larger than the client download limit", async () => {
  assert.equal(MAX_APK_BYTES, 250 * 1024 * 1024);
  const asset = {
    name: "WearTAK-Plugin-Example-1.apk",
    size: MAX_APK_BYTES + 1,
    browser_download_url: "https://github.com/aegorsuch/weartak-plugin-example/releases/download/v1/app.apk",
  };
  const catalog = await buildCatalog(
    { schemaVersion: 1, repositories: [source()] },
    {
      fetchImpl: fetchForRelease([asset]),
      inspect: async () => assert.fail("Oversized APK must not be downloaded."),
    },
  );
  assert.deepEqual(catalog.plugins, []);
});

test("rejects insecure release redirects and duplicate packages", async () => {
  const redirectAssets = [{
    name: "WearTAK-Plugin-Example-1.apk",
    size: 1,
    browser_download_url: "https://github.com/aegorsuch/weartak-plugin-example/releases/download/v1/app.apk",
  }];
  const redirectFetch = async (url) => {
    if (url.toString().startsWith("https://api.github.com/")) {
      return new Response(JSON.stringify({
        draft: false,
        prerelease: false,
        assets: redirectAssets,
      }), { status: 200 });
    }
    return new Response(null, {
      status: 302,
      headers: { location: "http://malicious.example/app.apk" },
    });
  };
  await assert.rejects(
    buildCatalog(
      { schemaVersion: 1, repositories: [source()] },
      { fetchImpl: redirectFetch },
    ),
    /HTTPS URL/,
  );

  const duplicate = source({ owner: "another-owner", repo: "another-plugin", id: "another" });
  await assert.rejects(
    buildCatalog(
      {
        schemaVersion: 1,
        repositories: [
          source({ expectedSignerSha256: "b".repeat(64) }),
          { ...duplicate, packageName: "org.example.plugin", expectedSignerSha256: "b".repeat(64) },
        ],
      },
      {
        fetchImpl: async (url) => {
          const parsed = new URL(url);
          if (parsed.hostname === "api.github.com") {
            const repo = parsed.pathname.split("/")[3];
            const asset = {
              name: "WearTAK-Plugin-Example-1.apk",
              size: 3,
              browser_download_url: `https://github.com/${parsed.pathname.split("/")[2]}/${repo}/releases/download/v1/app.apk`,
            };
            return new Response(JSON.stringify({
              draft: false,
              prerelease: false,
              assets: [asset],
            }), { status: 200 });
          }
          return new Response(Buffer.from("apk"), { status: 200 });
        },
        inspect: async () => ({
          packageName: "org.example.plugin",
          versionName: "1.0",
          versionCode: 1,
          signerSha256: "b".repeat(64),
        }),
      },
    ),
    /duplicate APK package names/,
  );
});

test("extracts a single signer digest and rejects ambiguous signer sets", () => {
  assert.equal(
    parseSignerSha256("Signer #1 certificate SHA-256 digest: AA:".concat("AB:".repeat(30), "AC")),
    "aa".concat("ab".repeat(30), "ac"),
  );
  assert.throws(
    () => parseSignerSha256("Signer #1 certificate SHA-256 digest: " + "aa".repeat(32) +
      "\nSigner #2 certificate SHA-256 digest: " + "bb".repeat(32)),
    /exactly one/,
  );
});

test("signs exact catalog bytes with the configured Ed25519 key", () => {
  const { privateKey, publicKey } = generateKeyPairSync("ed25519");
  const publicKeyBase64 = publicKey.export({ format: "der", type: "spki" }).toString("base64");
  const privateKeyPem = privateKey.export({ format: "pem", type: "pkcs8" }).toString();
  const catalog = { schemaVersion: 1, plugins: [] };
  const envelope = JSON.parse(signCatalog(catalog, privateKeyPem, publicKeyBase64));
  const payload = Buffer.from(envelope.payload, "base64");

  assert.deepEqual(JSON.parse(payload.toString("utf8")), catalog);
  assert.equal(
    verify(null, payload, publicKey, Buffer.from(envelope.signature, "base64")),
    true,
  );
  assert.throws(
    () => signCatalog(catalog, privateKeyPem, "invalid"),
    /does not match/,
  );
});

test("accepts only a valid previously signed catalog", async () => {
  const keys = generateKeyPairSync("ed25519");
  const publicKeyBase64 = keys.publicKey.export({ format: "der", type: "spki" }).toString("base64");
  const privateKeyPem = keys.privateKey.export({ format: "pem", type: "pkcs8" }).toString();
  const envelope = signCatalog({ schemaVersion: 1, plugins: [] }, privateKeyPem, publicKeyBase64);
  const catalog = await fetchPreviousCatalog(
    async () => new Response(envelope, { status: 200 }),
    publicKeyBase64,
  );
  assert.deepEqual(catalog, { schemaVersion: 1, plugins: [] });

  await assert.rejects(
    fetchPreviousCatalog(
      async () => new Response(envelope.replace(/.$/, "x"), { status: 200 }),
      publicKeyBase64,
    ),
    /signature is invalid|envelope is invalid/,
  );
});
