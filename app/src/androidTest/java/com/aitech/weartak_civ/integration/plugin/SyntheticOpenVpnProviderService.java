package com.aitech.weartak_civ.integration.plugin;

import android.app.Service;
import android.content.Intent;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class SyntheticOpenVpnProviderService extends Service {
    private static final String TAG = "SyntheticOpenVpnProvider";
    private static final String DESCRIPTOR =
            "com.weartak.plugin.sdk.openvpn.v1.IOpenVpnRawTcpProviderV1";
    private static final int TRANSACTION_DIAGNOSTICS = IBinder.FIRST_CALL_TRANSACTION + 100;
    private static final int TRANSACTION_CONFIGURE = IBinder.FIRST_CALL_TRANSACTION + 101;
    private static final int TRANSACTION_GET_API_VERSION = IBinder.FIRST_CALL_TRANSACTION;
    private static final int TRANSACTION_GET_RUNTIME_STATE = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int TRANSACTION_OPEN_STREAM = IBinder.FIRST_CALL_TRANSACTION + 2;
    private static final int TRANSACTION_READ_STREAM = IBinder.FIRST_CALL_TRANSACTION + 3;
    private static final int TRANSACTION_WRITE_STREAM = IBinder.FIRST_CALL_TRANSACTION + 4;
    private static final int TRANSACTION_CLOSE_STREAM = IBinder.FIRST_CALL_TRANSACTION + 5;
    private static final int API_VERSION = 1;
    private static final int RUNTIME_CONNECTED = 2;
    private static final int RUNTIME_ERROR = 4;
    private static final int MAX_IO_BYTES = 16 * 1024;
    private static final int MAX_TIMEOUT_MS = 60_000;
    private static final int IO_TIMEOUT_RESULT = -7;
    private static final int MAX_CONFIGURED_PORTS = 3;
    private static final int MAX_ACTIVE_STREAMS = 4;
    private static final String SERVER_ID = "synthetic-server";
    private static final String PROFILE_ID = "synthetic-profile";
    private static final String LOOPBACK_ADDRESS = "127.0.0.1";
    private static final long CONNECTION_GENERATION = 1L;

    private static final class SyntheticStream {
        final String profileId;
        final Socket socket;

        SyntheticStream(String profileId, Socket socket) {
            this.profileId = profileId;
            this.socket = socket;
        }
    }

    private final AtomicLong nextStreamId = new AtomicLong();
    private final ConcurrentHashMap<Long, SyntheticStream> streams = new ConcurrentHashMap<>();
    private volatile int lastCallingUid = -1;
    private volatile Set<Integer> allowedPorts = Collections.emptySet();

    private final Binder provider = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws android.os.RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(DESCRIPTOR);
                return true;
            }
            if (code == TRANSACTION_CONFIGURE) {
                data.enforceInterface(DESCRIPTOR);
                if (rejectUnauthorized(Binder.getCallingUid(), reply)) return true;
                int count = data.readInt();
                List<Integer> ports = new ArrayList<>();
                if (count >= 1 && count <= MAX_CONFIGURED_PORTS) {
                    for (int i = 0; i < count; i++) ports.add(data.readInt());
                }
                boolean valid = ports.size() == count;
                Set<Integer> distinct = ConcurrentHashMap.newKeySet();
                for (int port : ports) {
                    valid &= port >= 1 && port <= 65535 && distinct.add(port);
                }
                if (valid) allowedPorts = distinct;
                reply.writeNoException();
                reply.writeInt(valid ? 0 : -1);
                return true;
            }
            if (code == TRANSACTION_DIAGNOSTICS) {
                data.enforceInterface(DESCRIPTOR);
                if (rejectUnauthorized(Binder.getCallingUid(), reply)) return true;
                reply.writeNoException();
                reply.writeInt(Process.myPid());
                reply.writeInt(Process.myUid());
                reply.writeInt(lastCallingUid);
                return true;
            }
            if (code < IBinder.FIRST_CALL_TRANSACTION ||
                    code > IBinder.FIRST_CALL_TRANSACTION + 5) {
                return super.onTransact(code, data, reply, flags);
            }

            data.enforceInterface(DESCRIPTOR);
            if (rejectUnauthorized(Binder.getCallingUid(), reply)) return true;
            switch (code) {
                case TRANSACTION_GET_API_VERSION:
                    reply.writeNoException();
                    reply.writeInt(API_VERSION);
                    return true;
                case TRANSACTION_GET_RUNTIME_STATE:
                    String requestedProfile = data.readString();
                    reply.writeNoException();
                    reply.writeInt(PROFILE_ID.equals(requestedProfile)
                            ? RUNTIME_CONNECTED : RUNTIME_ERROR);
                    return true;
                case TRANSACTION_OPEN_STREAM:
                    String serverId = data.readString();
                    String profileId = data.readString();
                    String endpointAddress = data.readString();
                    int port = data.readInt();
                    long generation = data.readLong();
                    long timeoutMs = data.readLong();
                    reply.writeNoException();
                    reply.writeLong(openStream(
                            serverId, profileId, endpointAddress, port, generation, timeoutMs));
                    return true;
                case TRANSACTION_READ_STREAM:
                    String readProfileId = data.readString();
                    long readStreamId = data.readLong();
                    byte[] destination = data.createByteArray();
                    int offset = data.readInt();
                    int length = data.readInt();
                    long readTimeoutMs = data.readLong();
                    int readResult = readStream(
                            readProfileId, readStreamId, destination, offset, length, readTimeoutMs);
                    reply.writeNoException();
                    reply.writeInt(readResult);
                    reply.writeByteArray(destination);
                    return true;
                case TRANSACTION_WRITE_STREAM:
                    String writeProfileId = data.readString();
                    long writeStreamId = data.readLong();
                    byte[] source = data.createByteArray();
                    int writeOffset = data.readInt();
                    int writeLength = data.readInt();
                    data.readLong();
                    reply.writeNoException();
                    reply.writeInt(writeStream(
                            writeProfileId, writeStreamId, source, writeOffset, writeLength));
                    return true;
                case TRANSACTION_CLOSE_STREAM:
                    String closeProfileId = data.readString();
                    long closeStreamId = data.readLong();
                    reply.writeNoException();
                    reply.writeInt(closeStream(closeProfileId, closeStreamId));
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return provider;
    }

    @Override
    public void onDestroy() {
        for (Long streamId : streams.keySet().toArray(new Long[0])) {
            closeStream(PROFILE_ID, streamId);
        }
        super.onDestroy();
    }

    private boolean rejectUnauthorized(int callingUid, Parcel reply) {
        if (callingUid == getHostUid()) {
            lastCallingUid = callingUid;
            return false;
        }
        reply.writeException(new SecurityException(
                "Synthetic provider accepts only instrumentation host UID"));
        return true;
    }

    private int getHostUid() {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(
                    getPackageName(), PackageManager.GET_INSTRUMENTATION);
            InstrumentationInfo[] instrumentation = packageInfo.instrumentation;
            if (instrumentation == null || instrumentation.length != 1) {
                throw new IllegalStateException("Synthetic provider instrumentation metadata missing");
            }
            return getPackageManager().getApplicationInfo(instrumentation[0].targetPackage, 0).uid;
        } catch (PackageManager.NameNotFoundException error) {
            throw new IllegalStateException("Could not resolve instrumentation host UID", error);
        }
    }

    private long openStream(
            String serverId,
            String profileId,
            String endpointAddress,
            int port,
            long generation,
            long timeoutMs) {
        if (!SERVER_ID.equals(serverId) || !PROFILE_ID.equals(profileId) ||
                !LOOPBACK_ADDRESS.equals(endpointAddress) || !allowedPorts.contains(port) ||
                generation != CONNECTION_GENERATION || timeoutMs < 1 ||
                timeoutMs > MAX_TIMEOUT_MS || streams.size() >= MAX_ACTIVE_STREAMS) {
            return -1L;
        }
        try {
            InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(loopback, port), (int) timeoutMs);
            synchronized (streams) {
                if (streams.size() >= MAX_ACTIVE_STREAMS) {
                    socket.close();
                    return -1L;
                }
                long streamId = nextStreamId.incrementAndGet();
                streams.put(streamId, new SyntheticStream(PROFILE_ID, socket));
                return streamId;
            }
        } catch (IOException error) {
            Log.w(TAG, "Synthetic loopback stream open failed", error);
            return -1L;
        }
    }

    private int readStream(
            String profileId,
            long streamId,
            byte[] destination,
            int offset,
            int length,
            long timeoutMs) {
        SyntheticStream stream = streams.get(streamId);
        if (stream == null || !stream.profileId.equals(profileId)) return -9;
        if (destination == null || !validRange(destination.length, offset, length) ||
                length > MAX_IO_BYTES) return -1;
        try {
            stream.socket.setSoTimeout((int) Math.max(1, Math.min(timeoutMs, MAX_TIMEOUT_MS)));
            int result = stream.socket.getInputStream().read(destination, offset, length);
            return result < 0 ? 0 : result;
        } catch (SocketTimeoutException ignored) {
            return IO_TIMEOUT_RESULT;
        } catch (IOException error) {
            Log.w(TAG, "Synthetic loopback stream read failed", error);
            return -1;
        }
    }

    private int writeStream(
            String profileId,
            long streamId,
            byte[] source,
            int offset,
            int length) {
        SyntheticStream stream = streams.get(streamId);
        if (stream == null || !stream.profileId.equals(profileId)) return -9;
        if (source == null || !validRange(source.length, offset, length) ||
                length > MAX_IO_BYTES) return -1;
        try {
            stream.socket.getOutputStream().write(source, offset, length);
            stream.socket.getOutputStream().flush();
            return length;
        } catch (IOException error) {
            Log.w(TAG, "Synthetic loopback stream write failed", error);
            return -1;
        }
    }

    private int closeStream(String profileId, long streamId) {
        SyntheticStream stream = streams.get(streamId);
        if (stream == null || !stream.profileId.equals(profileId)) return 0;
        streams.remove(streamId, stream);
        try {
            stream.socket.close();
            return 0;
        } catch (IOException error) {
            Log.w(TAG, "Synthetic loopback stream close failed", error);
            return -1;
        }
    }

    private static boolean validRange(int size, int offset, int length) {
        return offset >= 0 && length > 0 && offset <= size - length;
    }
}
