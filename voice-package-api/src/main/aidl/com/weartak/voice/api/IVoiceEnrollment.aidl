package com.weartak.voice.api;
import android.os.Bundle;
import com.weartak.voice.api.IVoiceRequestSigner;
interface IVoiceEnrollment {
    Bundle profiles();
    Bundle enroll(String serverId, String installationId, in byte[] publicKey, IVoiceRequestSigner signer);
}
