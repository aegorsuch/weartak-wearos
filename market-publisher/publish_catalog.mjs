import { execFile as execFileCallback } from "node:child_process";
import {
  createHash,
  createPrivateKey,
  createPublicKey,
  sign,
  verify,
} from "node:crypto";
import { mkdtemp, readFile, rm, writeFile, mkdir } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";

const execFile = promisify(execFileCallback);
export const MAX_APK_BYTES = 250 * 1024 * 1024;
const MAX_CATALOG_BYTES = 512 * 1024;
const MAX_PLUGINS = 200;
const PUBLISHED_CATALOG_URL = "https://aegorsuch.github.io/weartak-wearos/catalog.json";
const PACKAGE_PATTERN = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;
const REDIRECT_STATUSES = new Set([301, 302, 303, 307, 308]);

function requireHttps(rawUrl, label) {
  let url;
  try {
    url = new URL(rawUrl);
  } catch {
    throw new Error(`${label} is not a valid URL.`);
  }
  if (url.protocol !== "https:" || url.username || url.password || url.hash) {
    throw new Error(`${label} must be an HTTPS URL without credentials or a fragment.`);
  }
  return url;
}

async function fetchHttps(fetchImpl, rawUrl, accept, { githubAssetRedirects = false } = {}) {
  let url = requireHttps(rawUrl, "Request URL");
  for (let redirectCount = 0; redirectCount <= 5; redirectCount += 1) {
    const response = await fetchImpl(url, {
      headers: {
        Accept: accept,
        "User-Agent": "WearTAK-Market-Catalog-Publisher",
      },
      redirect: "manual",
    });
    if (!REDIRECT_STATUSES.has(response.status)) return response;
    const location = response.headers.get("location");
    await response.body?.cancel();
    if (!location || redirectCount === 5) {
      throw new Error("Release request exceeded redirect limit.");
    }
    const nextUrl = requireHttps(new URL(location, url).toString(), "Redirect URL");
    if (githubAssetRedirects &&
        nextUrl.hostname !== "github.com" &&
        nextUrl.hostname !== "githubusercontent.com" &&
        !nextUrl.hostname.endsWith(".githubusercontent.com")) {
      throw new Error("GitHub release APK redirected to an untrusted host.");
    }
    url = nextUrl;
  }
  throw new Error("Release request exceeded redirect limit.");
}

async function getJson(fetchImpl, url) {
  const response = await fetchHttps(fetchImpl, url, "application/vnd.github+json");
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`GitHub API request failed (HTTP ${response.status}).`);
  return response.json();
}

export async function fetchPreviousCatalog(fetchImpl, publicKeyBase64) {
  const response = await fetchHttps(fetchImpl, PUBLISHED_CATALOG_URL, "application/json");
  if (response.status === 404) return null;
  if (!response.ok) {
    throw new Error(`Previously published catalog request failed (HTTP ${response.status}).`);
  }
  const bytes = await readLimitedBody(response, MAX_CATALOG_BYTES, "Previously published catalog");
  let envelope;
  let payload;
  let signature;
  let publicKey;
  try {
    envelope = JSON.parse(bytes.toString("utf8"));
    payload = Buffer.from(envelope.payload, "base64");
    signature = Buffer.from(envelope.signature, "base64");
    publicKey = createPublicKey({
      key: Buffer.from(publicKeyBase64, "base64"),
      format: "der",
      type: "spki",
    });
  } catch {
    throw new Error("Previously published catalog envelope is invalid.");
  }
  if (!verify(null, payload, publicKey, signature)) {
    throw new Error("Previously published catalog signature is invalid.");
  }
  let catalog;
  try {
    catalog = JSON.parse(payload.toString("utf8"));
  } catch {
    throw new Error("Previously published catalog payload is invalid.");
  }
  if (catalog.schemaVersion !== 1 || !Array.isArray(catalog.plugins) ||
      catalog.plugins.length > MAX_PLUGINS) {
    throw new Error("Previously published catalog schema is invalid.");
  }
  return catalog;
}

async function readLimitedBody(response, limit, label = "Release APK") {
  if (!response.body) throw new Error(`${label} response is empty.`);
  const reader = response.body.getReader();
  const chunks = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > limit) {
      await reader.cancel();
      throw new Error(`${label} exceeds size limit.`);
    }
    chunks.push(Buffer.from(value));
  }
  if (total === 0) throw new Error(`${label} response is empty.`);
  return Buffer.concat(chunks, total);
}

export function parseSignerSha256(apksignerOutput) {
  const matches = [...apksignerOutput.matchAll(
    /^\s*Signer #\d+ certificate SHA-256 digest:\s*([0-9a-f:]+)\s*$/gim,
  )];
  if (matches.length !== 1) {
    throw new Error("APK must have exactly one verifiable signing certificate.");
  }
  const digest = matches[0][1].replaceAll(":", "").toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(digest)) {
    throw new Error("APK signing certificate digest is invalid.");
  }
  return digest;
}

async function runApkTool(binary, args) {
  try {
    const { stdout } = await execFile(binary, args, {
      encoding: "utf8",
      windowsHide: true,
      shell: process.platform === "win32",
    });
    return stdout.trim();
  } catch (error) {
    throw new Error(`APK inspection failed (${path.basename(binary)}): ${error.message}`);
  }
}

async function inspectApk(apkBytes) {
  const tempDir = await mkdtemp(path.join(os.tmpdir(), "weartak-market-"));
  const apkPath = path.join(tempDir, "release.apk");
  try {
    await writeFile(apkPath, apkBytes, { flag: "wx" });
    const packageName = await runApkTool(
      process.env.APKANALYZER || "apkanalyzer",
      ["manifest", "application-id", apkPath],
    );
    const versionCodeText = await runApkTool(
      process.env.APKANALYZER || "apkanalyzer",
      ["manifest", "version-code", apkPath],
    );
    const versionName = await runApkTool(
      process.env.APKANALYZER || "apkanalyzer",
      ["manifest", "version-name", apkPath],
    );
    const signerOutput = await runApkTool(
      process.env.APKSIGNER || "apksigner",
      ["verify", "--verbose", "--print-certs", apkPath],
    );
    const versionCode = Number(versionCodeText);
    if (!PACKAGE_PATTERN.test(packageName) || !Number.isSafeInteger(versionCode) ||
        versionCode <= 0 || !versionName) {
      throw new Error("APK package or version metadata is invalid.");
    }
    return {
      packageName,
      versionName,
      versionCode,
      signerSha256: parseSignerSha256(signerOutput),
    };
  } finally {
    await rm(tempDir, { recursive: true, force: true });
  }
}

function validateSource(source) {
  const textLimits = {
    owner: 100,
    repo: 100,
    id: 100,
    name: 100,
    description: 1000,
    publisher: 100,
  };
  for (const [field, maxLength] of Object.entries(textLimits)) {
    if (typeof source[field] !== "string" || !source[field].trim()) {
      throw new Error(`Approved repository has invalid '${field}'.`);
    }
    if (source[field].length > maxLength) {
      throw new Error(`Approved repository '${source.repo}' has an oversized '${field}'.`);
    }
  }
  if (!/^[A-Za-z0-9_.-]+$/.test(source.owner) ||
      !/^[A-Za-z0-9_.-]+$/.test(source.repo)) {
    throw new Error("Approved repository name is invalid.");
  }
  if (!Number.isSafeInteger(source.minHostVersionCode) || source.minHostVersionCode <= 0 ||
      (source.maxHostVersionCode !== null &&
        (!Number.isSafeInteger(source.maxHostVersionCode) ||
          source.maxHostVersionCode < source.minHostVersionCode))) {
    throw new Error(`Approved repository '${source.repo}' has invalid host-version bounds.`);
  }
  if (!["official", "community"].includes(source.publisherType) ||
      typeof source.hostUpdate !== "boolean") {
    throw new Error(`Approved repository '${source.repo}' has invalid publisher policy.`);
  }
  if (source.hostUpdate) {
    if (source.publisherType !== "official" ||
        source.packageName !== "com.aitech.weartak_civ" ||
        `${source.owner.toLowerCase()}/${source.repo.toLowerCase()}` !== "aegorsuch/weartak-wearos") {
      throw new Error("Only the official WearTAK repository may publish host updates.");
    }
  } else if (source.publisherType !== "community" ||
      typeof source.packageName !== "string" ||
      !PACKAGE_PATTERN.test(source.packageName) ||
      !/^[0-9a-f]{64}$/.test(source.expectedSignerSha256 || "")) {
    throw new Error("Community entries require an approved package and signer.");
  }
  if (typeof source.assetPattern !== "string" || source.assetPattern.length > 200) {
    throw new Error(`Approved repository '${source.repo}' has an invalid asset pattern.`);
  }
  try {
    new RegExp(source.assetPattern);
  } catch {
    throw new Error(`Approved repository '${source.repo}' has an invalid asset pattern.`);
  }
}

function releaseFingerprint(release, assets) {
  const releaseAssets = assets.map(({ id, name, size, updated_at, browser_download_url }) => ({
    id,
    name,
    size,
    updated_at,
    browser_download_url,
  }));
  const content = JSON.stringify({
    tagName: release.tag_name,
    publishedAt: release.published_at,
    assets: releaseAssets,
  });
  return createHash("sha256").update(content).digest("hex");
}

function matchesApprovedSource(entry, source, fingerprint, eligibleAssets) {
  return entry &&
    entry.publisherReleaseFingerprint === fingerprint &&
    eligibleAssets.some((asset) => asset.browser_download_url === entry.apkUrl) &&
    entry.id === source.id &&
    entry.name === source.name &&
    entry.description === source.description &&
    entry.publisher === source.publisher &&
    entry.publisherType === source.publisherType &&
    entry.packageName === source.packageName &&
    entry.minHostVersionCode === source.minHostVersionCode &&
    entry.maxHostVersionCode === source.maxHostVersionCode &&
    entry.hostUpdate === source.hostUpdate &&
    (!source.expectedSignerSha256 ||
      entry.signerSha256 === source.expectedSignerSha256.toLowerCase());
}

async function inspectReleaseAssets(source, release, fetchImpl, inspect, previousEntry) {
  const assetPattern = new RegExp(source.assetPattern, "i");
  const assets = (release.assets || [])
    .filter((asset) => assetPattern.test(asset.name))
    .sort((left, right) => left.name.localeCompare(right.name));
  if (assets.length === 0) {
    throw new Error(`Approved repository '${source.repo}' has no matching APK in its latest release.`);
  }
  for (const asset of assets) {
    if (!asset.name.toLowerCase().endsWith(".apk") ||
        !Number.isSafeInteger(asset.size) || asset.size <= 0) {
      throw new Error(`Approved repository '${source.repo}' has an invalid APK asset.`);
    }
  }
  const eligibleAssets = assets.filter((asset) =>
    Number.isSafeInteger(asset.size) && asset.size > 0 && asset.size <= MAX_APK_BYTES);
  if (eligibleAssets.length === 0) {
    console.warn(`Skipping '${source.repo}': matching APKs exceed the 250 MiB client limit.`);
    return null;
  }
  const fingerprint = releaseFingerprint(release, assets);
  if (matchesApprovedSource(previousEntry, source, fingerprint, eligibleAssets)) {
    return { entry: previousEntry, reused: true };
  }

  const candidates = [];
  for (const asset of eligibleAssets) {
    const downloadUrl = requireHttps(asset.browser_download_url, "APK URL");
    if (downloadUrl.hostname !== "github.com" ||
        !downloadUrl.pathname.includes(`/${source.owner}/${source.repo}/releases/download/`)) {
      throw new Error(`Approved repository '${source.repo}' APK is not hosted by its GitHub release.`);
    }
    const response = await fetchHttps(
      fetchImpl,
      downloadUrl.toString(),
      "application/octet-stream",
      { githubAssetRedirects: true },
    );
    if (!response.ok) {
      throw new Error(`APK download failed for '${source.repo}' (HTTP ${response.status}).`);
    }
    const apkBytes = await readLimitedBody(response, MAX_APK_BYTES);
    const metadata = await inspect(apkBytes, asset);
    if (source.packageName && metadata.packageName !== source.packageName) {
      throw new Error(`WearTAK APK package mismatch in '${source.repo}'.`);
    }
    if (source.expectedSignerSha256 &&
        metadata.signerSha256 !== source.expectedSignerSha256.toLowerCase()) {
      throw new Error(`APK signer mismatch in approved repository '${source.repo}'.`);
    }
    if (source.hostUpdate && metadata.packageName !== "com.aitech.weartak_civ") {
      throw new Error("WearTAK update APK does not have the WearTAK application ID.");
    }
    if (!source.hostUpdate && metadata.packageName === "com.aitech.weartak_civ") {
      throw new Error("Community repository cannot publish the WearTAK host package.");
    }
    candidates.push({
      asset,
      apkSha256: createHash("sha256").update(apkBytes).digest("hex"),
      metadata,
    });
  }

  candidates.sort((left, right) => right.metadata.versionCode - left.metadata.versionCode);
  if (candidates.length > 1 &&
      candidates[0].metadata.versionCode === candidates[1].metadata.versionCode &&
      candidates[0].apkSha256 !== candidates[1].apkSha256) {
    throw new Error(`Approved repository '${source.repo}' has ambiguous APKs at the same version.`);
  }
  return { ...candidates[0], fingerprint, reused: false };
}

export async function buildCatalog(config, {
  fetchImpl = globalThis.fetch,
  inspect = inspectApk,
  previousCatalog = null,
} = {}) {
  if (config?.schemaVersion !== 1 || !Array.isArray(config.repositories) ||
      !Array.isArray(config.pendingRepositories || [])) {
    throw new Error("Approved repository configuration schema is invalid.");
  }
  if (config.repositories.length > MAX_PLUGINS) {
    throw new Error("Approved repository count exceeds catalog limit.");
  }

  const ids = new Set();
  const repos = new Set();
  for (const source of config.repositories) {
    validateSource(source);
    const fullName = `${source.owner.toLowerCase()}/${source.repo.toLowerCase()}`;
    if (ids.has(source.id) || repos.has(fullName)) {
      throw new Error("Approved repository configuration contains duplicates.");
    }
    ids.add(source.id);
    repos.add(fullName);
  }
  const pendingRepos = new Set();
  for (const repository of config.pendingRepositories || []) {
    if (typeof repository !== "string" ||
        !/^[A-Za-z0-9_.-]+\/weartak-plugin-[A-Za-z0-9_.-]+$/.test(repository)) {
      throw new Error("Pending repository list contains an invalid repository.");
    }
    const fullName = repository.toLowerCase();
    if (repos.has(fullName) || pendingRepos.has(fullName)) {
      throw new Error("Approved and pending repository lists contain duplicates.");
    }
    pendingRepos.add(fullName);
  }

  const plugins = [];
  const previousEntries = new Map(
    (previousCatalog?.plugins || []).map((plugin) => [plugin.id, plugin]),
  );
  for (const source of config.repositories) {
    const apiUrl = `https://api.github.com/repos/${source.owner}/${source.repo}/releases/latest`;
    const release = await getJson(fetchImpl, apiUrl);
    if (release === null) {
      throw new Error(`Approved repository '${source.repo}' has no stable release.`);
    }
    if (release.draft || release.prerelease) continue;

    const selected = await inspectReleaseAssets(
      source,
      release,
      fetchImpl,
      inspect,
      previousEntries.get(source.id),
    );
    if (selected === null) continue;
    if (selected.reused) {
      plugins.push(selected.entry);
      continue;
    }
    const assetUrl = requireHttps(selected.asset.browser_download_url, "APK URL").toString();
    plugins.push({
      id: source.id,
      name: source.name,
      description: source.description,
      publisher: source.publisher,
      publisherType: source.publisherType,
      packageName: selected.metadata.packageName,
      versionName: selected.metadata.versionName,
      versionCode: selected.metadata.versionCode,
      minHostVersionCode: source.minHostVersionCode,
      maxHostVersionCode: source.maxHostVersionCode,
      apkUrl: assetUrl,
      apkSha256: selected.apkSha256,
      signerSha256: selected.metadata.signerSha256,
      sourceUrl: `https://github.com/${source.owner}/${source.repo}`,
      hostUpdate: source.hostUpdate,
      publisherReleaseFingerprint: selected.fingerprint,
    });
  }

  const packageNames = plugins.map((plugin) => plugin.packageName);
  if (new Set(packageNames).size !== packageNames.length) {
    throw new Error("Catalog sources contain duplicate APK package names.");
  }
  plugins.sort((left, right) => left.id.localeCompare(right.id));
  return { schemaVersion: 1, plugins };
}

export function signCatalog(catalog, privateKeyPem, expectedPublicKeyBase64) {
  let privateKey;
  try {
    privateKey = createPrivateKey(privateKeyPem);
  } catch {
    throw new Error("Catalog signing key is not a valid PEM private key.");
  }
  if (privateKey.asymmetricKeyType !== "ed25519") {
    throw new Error("Catalog signing key must use Ed25519.");
  }
  const publicKeyBytes = createPublicKey(privateKey)
    .export({ format: "der", type: "spki" });
  const publicKeyBase64 = Buffer.from(publicKeyBytes).toString("base64");
  if (!expectedPublicKeyBase64 || publicKeyBase64 !== expectedPublicKeyBase64.trim()) {
    throw new Error("Catalog signing key does not match configured public key.");
  }

  const payload = Buffer.from(JSON.stringify(catalog), "utf8");
  if (payload.length > MAX_CATALOG_BYTES || catalog.plugins.length > MAX_PLUGINS) {
    throw new Error("Catalog exceeds supported size or entry count.");
  }
  const signature = sign(null, payload, privateKey);
  return JSON.stringify({
    payload: payload.toString("base64"),
    signature: signature.toString("base64"),
  });
}

async function main() {
  const currentDir = path.dirname(fileURLToPath(import.meta.url));
  const configPath = process.env.MARKET_APPROVED_REPOSITORIES ||
    path.join(currentDir, "approved-repositories.json");
  const config = JSON.parse(await readFile(configPath, "utf8"));
  const privateKey = process.env.MARKET_CATALOG_SIGNING_KEY || "";
  const publicKey = process.env.MARKET_CATALOG_PUBLIC_KEY || "";
  signCatalog({ schemaVersion: 1, plugins: [] }, privateKey, publicKey);
  const previousCatalog = await fetchPreviousCatalog(globalThis.fetch, publicKey);
  const catalog = await buildCatalog(config, { previousCatalog });
  const envelope = signCatalog(catalog, privateKey, publicKey);
  const outputDir = process.env.MARKET_OUTPUT_DIR || path.join(currentDir, "site");
  await mkdir(outputDir, { recursive: true });
  await writeFile(path.join(outputDir, "catalog.json"), envelope, { mode: 0o644 });
  console.log(`Published catalog envelope with ${catalog.plugins.length} release(s).`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
