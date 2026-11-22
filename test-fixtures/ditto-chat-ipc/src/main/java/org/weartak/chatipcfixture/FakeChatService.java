package org.weartak.chatipcfixture;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONException;
import org.json.JSONObject;

public final class FakeChatService extends Service {
    private static final String HOST = "com.aitech.weartak_civ.ditto.diagnostics.fixture";
    private static final String DESCRIPTOR = "org.weartak.chatipcfixture.IFakeChat";
    private IBinder session;
    private long generation;

    private boolean hasSameCurrentSigner() {
        try {
            PackageInfo host = getPackageManager().getPackageInfo(
                    HOST, PackageManager.GET_SIGNING_CERTIFICATES);
            PackageInfo self = getPackageManager().getPackageInfo(
                    getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            Signature[] hostSigners = host.signingInfo.getApkContentsSigners();
            Signature[] selfSigners = self.signingInfo.getApkContentsSigners();
            return hostSigners.length == 1 && selfSigners.length == 1
                    && hostSigners[0].equals(selfSigners[0]);
        } catch (PackageManager.NameNotFoundException e) {
            throw new SecurityException("Fixture package identity unavailable", e);
        }
    }

    private final Binder binder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            data.enforceInterface(DESCRIPTOR);
            int uid = Binder.getCallingUid();
            String[] packages = getPackageManager().getPackagesForUid(uid);
            if (uid == Process.myUid() || packages == null || packages.length != 1
                    || !HOST.equals(packages[0])
                    || getPackageManager().checkSignatures(uid, Process.myUid())
                    != PackageManager.SIGNATURE_MATCH || !hasSameCurrentSigner()) {
                throw new SecurityException("Untrusted fixture caller");
            }
            if (code == 1) {
                IBinder requested = data.readStrongBinder();
                if (requested == null) throw new SecurityException("Missing session");
                synchronized (FakeChatService.this) {
                    session = requested;
                    generation++;
                    reply.writeNoException();
                    reply.writeLong(generation);
                }
                return true;
            }
            if (code == 2 || code == 3) {
                IBinder requested = data.readStrongBinder();
                long requestedGeneration = data.readLong();
                synchronized (FakeChatService.this) {
                    if (session == null || !session.equals(requested)
                            || requestedGeneration != generation) {
                        throw new SecurityException("Stale fixture session");
                    }
                    if (code == 3) {
                        session = null;
                        reply.writeNoException();
                        return true;
                    }
                }
                int bodyLength = data.readInt();
                long timestamp = data.readLong();
                if (bodyLength < -2 || bodyLength > 262145) {
                    throw new IllegalArgumentException("Invalid fixture size");
                }
                byte[] payload;
                try {
                    JSONObject doc = new JSONObject();
                    doc.put("_id", "fixture-message");
                    doc.put("_v", 2);
                    doc.put("_r", false);
                    doc.put("a", "fixture-peer");
                    doc.put("b", timestamp);
                    doc.put("d", "fixture-sender");
                    doc.put("e", "SENDER");
                    char[] body = new char[Math.max(bodyLength, 0)];
                    Arrays.fill(body, 'x');
                    doc.put("msg", "snow \u2603 & < > \" " + new String(body));
                    doc.put("room", "WATCH");
                    doc.put("roomId", "watch");
                    doc.put("parent", "RootContactGroup");
                    doc.put("authorType", "a-f-G-U-C");
                    doc.put("authorLoc", "0.0,0.0,0.0");
                    if (bodyLength == -1) {
                        payload = "{".getBytes(StandardCharsets.UTF_8);
                    } else if (bodyLength == -2) {
                        payload = new byte[263169];
                        Arrays.fill(payload, (byte) 'x');
                    } else {
                        payload = doc.toString().getBytes(StandardCharsets.UTF_8);
                    }
                    ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                    Thread writer = new Thread(() -> {
                        try (ParcelFileDescriptor.AutoCloseOutputStream out =
                                     new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                            out.write(payload);
                        } catch (IOException ignored) {
                            // Reader cancellation closes the test pipe.
                        }
                    }, "fixture-pipe");
                    writer.start();
                    reply.writeNoException();
                    pipe[0].writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                } catch (IOException | JSONException error) {
                    throw new IllegalStateException("Could not create fixture stream", error);
                }
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    @Override public IBinder onBind(Intent intent) {
        return binder;
    }
}
