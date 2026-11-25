package se.takcampus.voice;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.net.Socket;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.UUID;
import javax.net.ssl.X509KeyManager;

/** Matches exactly one session key for the JSSE handshake. Private keys stay in Android Keystore. */
public final class WifiPairingIdentity implements X509KeyManager, AutoCloseable {
    private final KeyStore store;
    private final String alias;
    private final X509Certificate certificate;
    private final PrivateKey key;

    public WifiPairingIdentity() throws Exception {
        store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        alias = "weartak-wifi-" + UUID.randomUUID();
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .build());
            key = generator.generateKeyPair().getPrivate();
            certificate = (X509Certificate) store.getCertificate(alias);
            if (certificate == null) throw new IllegalStateException("Pairing certificate was not created");
        } catch (Exception failure) {
            close();
            throw failure;
        }
    }

    public X509Certificate certificate() { return certificate; }
    @Override public String[] getClientAliases(String type, Principal[] issuers) { return aliases(type); }
    @Override public String[] getServerAliases(String type, Principal[] issuers) { return aliases(type); }
    private String[] aliases(String type) { return isEc(type) ? new String[]{alias} : null; }
    private static boolean isEc(String type) { return type != null && (type.equals("EC") || type.startsWith("EC_")); }
    @Override public String chooseClientAlias(String[] types, Principal[] issuers, Socket socket) {
        return types != null && Arrays.stream(types).anyMatch(WifiPairingIdentity::isEc) ? alias : null;
    }
    @Override public String chooseServerAlias(String type, Principal[] issuers, Socket socket) { return isEc(type) ? alias : null; }
    @Override public X509Certificate[] getCertificateChain(String requested) {
        return alias.equals(requested) ? new X509Certificate[]{certificate} : null;
    }
    @Override public PrivateKey getPrivateKey(String requested) { return alias.equals(requested) ? key : null; }
    @Override public void close() {
        try { store.deleteEntry(alias); }
        catch (java.security.KeyStoreException ignored) { /* No later session reuses this alias. */ }
    }
}
