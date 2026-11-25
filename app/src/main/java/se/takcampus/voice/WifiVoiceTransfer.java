package se.takcampus.voice;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Locale;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.bouncycastle.crypto.CryptoException;
import org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant;
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload;
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload;
import org.bouncycastle.crypto.agreement.jpake.JPAKERound3Payload;

/** One foreground Wi-Fi voice handoff, authenticated by J-PAKE and protected by pinned TLS. */
public final class WifiVoiceTransfer {
    public static final String SERVICE = "_weartak._tcp.";
    public static final String SERVICE_NAME_PREFIX = "WearTAK Voice ";
    public static final String PROTOCOL_VERSION = "1";
    public static final int MAX_ENVELOPE = 49152;
    public static final int SOCKET_TIMEOUT_MS = 6000;
    private static final int MAGIC = 0x57545731; // WTW1
    private static final int MAX_INTEGER = 385; // 3072-bit magnitude plus a sign byte.

    public enum Receipt {
        STAGED(1), BUSY(2), REJECTED(3), QUEUED(4), RECEIVER_UNAVAILABLE(5);

        private final int wireValue;
        Receipt(int wireValue) { this.wireValue = wireValue; }

        private static Receipt fromWire(int value) throws IOException {
            for (Receipt receipt : values()) if (receipt.wireValue == value) return receipt;
            throw new IOException("Unknown voice transfer receipt");
        }
    }

    public interface Receiver {
        Receipt stage(String envelope);
    }

    private WifiVoiceTransfer() {}

    public static Receipt send(Socket socket, String code, KeyManager identity, X509Certificate certificate, String envelope)
            throws IOException, GeneralSecurityException {
        byte[] payload = envelope.getBytes(StandardCharsets.UTF_8);
        if (payload.length == 0 || payload.length > MAX_ENVELOPE) {
            throw new IOException("Voice package size exceeds the transfer limit");
        }
        String watch = authenticate(socket, code, "p:" + fingerprint(certificate), "w:[0-9a-f]{64}");
        try (SSLSocket tls = wrap(socket, peerTls(identity, watch.substring(2), true), true)) {
            DataOutputStream out = new DataOutputStream(tls.getOutputStream());
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();
            return Receipt.fromWire(new DataInputStream(tls.getInputStream()).readUnsignedByte());
        }
    }

    public static Receipt receive(Socket socket, String code, KeyManager identity, X509Certificate certificate,
                                  Receiver receiver) throws IOException, GeneralSecurityException {
        String phone = authenticate(socket, code, "w:" + fingerprint(certificate), "p:[0-9a-f]{64}");
        try (SSLSocket tls = wrap(socket, peerTls(identity, phone.substring(2), false), false)) {
            DataInputStream in = new DataInputStream(tls.getInputStream());
            int size = in.readInt();
            if (size < 1 || size > MAX_ENVELOPE) throw new IOException("Invalid voice package size");
            byte[] payload = new byte[size];
            in.readFully(payload);
            String envelope = new String(payload, StandardCharsets.UTF_8);
            Receipt result = Arrays.equals(payload, envelope.getBytes(StandardCharsets.UTF_8))
                    ? receiver.stage(envelope) : Receipt.REJECTED;
            DataOutputStream out = new DataOutputStream(tls.getOutputStream());
            out.writeByte(result.wireValue);
            out.flush();
            return result;
        }
    }

    private static String authenticate(Socket socket, String code, String identity, String peerPattern)
            throws IOException, GeneralSecurityException {
        if (!code.matches("[0-9]{8}")) throw new IllegalArgumentException("Enter the eight-digit pairing code");
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        // Do not buffer either stream: the next bytes after key confirmation belong to TLS.
        DataInputStream in = new DataInputStream(socket.getInputStream());
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        char[] password = code.toCharArray();
        JPAKEParticipant participant;
        try {
            participant = new JPAKEParticipant(identity, password);
        } finally {
            Arrays.fill(password, '\0');
        }
        try {
            JPAKERound1Payload first = participant.createRound1PayloadToSend();
            out.writeInt(MAGIC);
            writeId(out, identity);
            writeInteger(out, first.getGx1());
            writeInteger(out, first.getGx2());
            writeProof(out, first.getKnowledgeProofForX1());
            writeProof(out, first.getKnowledgeProofForX2());
            out.flush();
            if (in.readInt() != MAGIC) throw new IOException("Unsupported Wi-Fi pairing protocol");
            String peer = readId(in, peerPattern);
            participant.validateRound1PayloadReceived(new JPAKERound1Payload(peer, readInteger(in),
                    readInteger(in), readProof(in), readProof(in)));

            JPAKERound2Payload second = participant.createRound2PayloadToSend();
            writeInteger(out, second.getA());
            writeProof(out, second.getKnowledgeProofForX2s());
            out.flush();
            participant.validateRound2PayloadReceived(new JPAKERound2Payload(peer, readInteger(in), readProof(in)));

            BigInteger key = participant.calculateKeyingMaterial();
            writeInteger(out, participant.createRound3PayloadToSend(key).getMacTag());
            out.flush();
            participant.validateRound3PayloadReceived(new JPAKERound3Payload(peer, readInteger(in)), key);
            return peer;
        } catch (CryptoException | IllegalArgumentException invalid) {
            throw new GeneralSecurityException("Wi-Fi pairing could not authenticate the watch and phone", invalid);
        }
    }

    private static void writeId(DataOutputStream out, String id) throws IOException {
        byte[] bytes = id.getBytes(StandardCharsets.US_ASCII);
        out.writeByte(bytes.length);
        out.write(bytes);
    }

    private static String readId(DataInputStream in, String pattern) throws IOException {
        int size = in.readUnsignedByte();
        if (size < 1 || size > 66) throw new IOException("Invalid pairing identity");
        byte[] bytes = new byte[size];
        in.readFully(bytes);
        String id = new String(bytes, StandardCharsets.US_ASCII);
        if (!id.matches(pattern)) throw new IOException("Unexpected pairing endpoint");
        return id;
    }

    private static void writeInteger(DataOutputStream out, BigInteger value) throws IOException {
        byte[] bytes = value.toByteArray();
        if (bytes.length > MAX_INTEGER) throw new IOException("Pairing integer exceeds the protocol limit");
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static BigInteger readInteger(DataInputStream in) throws IOException {
        int size = in.readUnsignedShort();
        if (size < 1 || size > MAX_INTEGER) throw new IOException("Invalid pairing integer");
        byte[] bytes = new byte[size];
        in.readFully(bytes);
        return new BigInteger(bytes);
    }

    private static void writeProof(DataOutputStream out, BigInteger[] proof) throws IOException {
        writeInteger(out, proof[0]);
        writeInteger(out, proof[1]);
    }

    private static BigInteger[] readProof(DataInputStream in) throws IOException {
        return new BigInteger[]{readInteger(in), readInteger(in)};
    }

    private static SSLContext peerTls(KeyManager identity, String pin, boolean client) throws GeneralSecurityException {
        byte[] expected = pin.getBytes(StandardCharsets.US_ASCII);
        X509TrustManager pinnedPeer = new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (client) throw new CertificateException("Expected a watch certificate");
                verify(chain);
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (!client) throw new CertificateException("Expected a phone certificate");
                verify(chain);
            }
            private void verify(X509Certificate[] chain) throws CertificateException {
                if (chain == null || chain.length != 1) throw new CertificateException("Invalid pairing certificate chain");
                chain[0].checkValidity();
                try {
                    byte[] actual = fingerprint(chain[0]).getBytes(StandardCharsets.US_ASCII);
                    if (!MessageDigest.isEqual(expected, actual)) throw new CertificateException("Peer identity changed");
                } catch (GeneralSecurityException invalid) {
                    throw new CertificateException("Peer certificate did not match the pairing", invalid);
                }
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(new KeyManager[]{identity}, new TrustManager[]{pinnedPeer}, null);
        return context;
    }

    private static String fingerprint(X509Certificate certificate) throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return hex.toString();
    }

    private static SSLSocket wrap(Socket socket, SSLContext context, boolean client) throws IOException {
        SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(
                socket, socket.getInetAddress().getHostAddress(), socket.getPort(), true);
        try {
            tls.setUseClientMode(client);
            if (!client) tls.setNeedClientAuth(true);
            tls.setEnabledProtocols(Arrays.stream(tls.getSupportedProtocols())
                    .filter(name -> name.equals("TLSv1.2") || name.equals("TLSv1.3")).toArray(String[]::new));
            tls.startHandshake();
            return tls;
        } catch (IOException | RuntimeException failed) {
            try { tls.close(); } catch (IOException closeError) { failed.addSuppressed(closeError); }
            throw failed;
        }
    }
}
