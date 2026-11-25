package se.takcampus.voice;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Locale;

/** Owns the Wi-Fi request, DNS-SD registration, and sockets for one visible pairing window. */
public final class WifiVoiceAdvertiser implements AutoCloseable {
    public interface Listener {
        void ready(String name, String code);
        void finished(String message, boolean staged);
    }

    private static final int WINDOW_MS = 120_000;
    private static final int MAX_ATTEMPTS = 4;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ConnectivityManager connectivity;
    private final NsdManager nsd;
    private final Listener listener;
    private final WifiVoiceTransfer.Receiver receiver;
    private final String name;
    private final String code;
    private final Runnable expiry = () -> finish("Pairing expired. Open Wi-Fi pairing again.", false);
    private volatile boolean closed;
    private Network wifi;
    private ServerSocket server;
    private Socket active;
    private boolean requestedWifi;
    private boolean requestedAdvertisement;

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
            if (closed || wifi != null) return;
            for (LinkAddress link : properties.getLinkAddresses()) {
                InetAddress address = link.getAddress();
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    wifi = network;
                    new Thread(() -> listen(network, address), "WearTAK-WiFi-Pair").start();
                    return;
                }
            }
        }
        @Override public void onLost(Network network) {
            if (network.equals(wifi)) finish("Watch Wi-Fi disconnected. Open pairing again.", false);
        }
        @Override public void onUnavailable() { finish("Connect the watch to Wi-Fi and try again.", false); }
    };

    private final NsdManager.RegistrationListener registration = new NsdManager.RegistrationListener() {
        @Override public void onServiceRegistered(NsdServiceInfo info) {
            main.post(() -> { if (!closed) listener.ready(info.getServiceName(), code); });
        }
        @Override public void onRegistrationFailed(NsdServiceInfo info, int error) {
            main.post(() -> finish("Could not advertise this watch on Wi-Fi.", false));
        }
        @Override public void onServiceUnregistered(NsdServiceInfo info) {}
        @Override public void onUnregistrationFailed(NsdServiceInfo info, int error) {}
    };

    public WifiVoiceAdvertiser(Context context, WifiVoiceTransfer.Receiver receiver, Listener listener) {
        connectivity = context.getSystemService(ConnectivityManager.class);
        nsd = context.getSystemService(NsdManager.class);
        this.receiver = receiver;
        this.listener = listener;
        SecureRandom random = new SecureRandom();
        name = WifiVoiceTransfer.SERVICE_NAME_PREFIX + String.format(Locale.ROOT, "%04X", random.nextInt(65536));
        code = String.format(Locale.ROOT, "%08d", random.nextInt(100_000_000));
    }

    public void start() {
        if (closed || requestedWifi) return;
        try {
            NetworkRequest request = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            connectivity.requestNetwork(request, networkCallback, main, 12_000);
            requestedWifi = true;
            main.postDelayed(expiry, WINDOW_MS);
        } catch (RuntimeException failure) {
            finish("Wi-Fi pairing is unavailable on this watch.", false);
        }
    }

    private void listen(Network network, InetAddress bindAddress) {
        try (WifiPairingIdentity identity = new WifiPairingIdentity(); ServerSocket listenerSocket = new ServerSocket()) {
            listenerSocket.bind(new InetSocketAddress(bindAddress, 0), 2);
            synchronized (this) {
                if (closed) return;
                server = listenerSocket;
            }
            int port = listenerSocket.getLocalPort();
            main.post(() -> advertise(network, port));
            for (int attempt = 0; attempt < MAX_ATTEMPTS && !closed; attempt++) {
                try (Socket socket = listenerSocket.accept()) {
                    synchronized (this) {
                        if (closed) return;
                        active = socket;
                    }
                    WifiVoiceTransfer.Receipt result = WifiVoiceTransfer.receive(socket, code, identity, identity.certificate(),
                            message -> closed ? WifiVoiceTransfer.Receipt.REJECTED : receiver.stage(message));
                    if (result == WifiVoiceTransfer.Receipt.STAGED) {
                        main.post(() -> finish("Voice channels received.", true));
                        return;
                    }
                    if (result == WifiVoiceTransfer.Receipt.QUEUED) {
                        main.post(() -> finish("Package delivered. Open Voice to check for review.", false));
                        return;
                    }
                    if (result == WifiVoiceTransfer.Receipt.RECEIVER_UNAVAILABLE) {
                        main.post(() -> finish("Enable the WearTAK Voice plugin, then retry.", false));
                        return;
                    }
                    if (result == WifiVoiceTransfer.Receipt.BUSY) {
                        main.post(() -> finish("Review the earlier voice package, then retry.", false));
                        return;
                    }
                } catch (IOException | java.security.GeneralSecurityException attemptFailed) {
                    if (closed) return;
                } finally {
                    synchronized (this) { active = null; }
                }
            }
            main.post(() -> finish("Pairing did not complete. Open pairing for a new code.", false));
        } catch (Exception failure) {
            main.post(() -> finish("Could not open the watch’s Wi-Fi pairing listener.", false));
        }
    }

    private void advertise(Network network, int port) {
        if (closed) return;
        try {
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceType(WifiVoiceTransfer.SERVICE);
            info.setServiceName(name);
            info.setPort(port);
            info.setAttribute("voice", WifiVoiceTransfer.PROTOCOL_VERSION);
            if (Build.VERSION.SDK_INT >= 33) info.setNetwork(network);
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
            requestedAdvertisement = true;
        } catch (RuntimeException failure) {
            finish("Could not advertise this watch on Wi-Fi.", false);
        }
    }

    private void finish(String message, boolean staged) {
        if (closed) return;
        close();
        listener.finished(message, staged);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        main.removeCallbacks(expiry);
        try { if (active != null) active.close(); } catch (IOException ignored) {}
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        if (requestedAdvertisement) {
            try { nsd.unregisterService(registration); } catch (RuntimeException ignored) {}
        }
        if (requestedWifi) {
            try { connectivity.unregisterNetworkCallback(networkCallback); } catch (RuntimeException ignored) {}
        }
    }
}
