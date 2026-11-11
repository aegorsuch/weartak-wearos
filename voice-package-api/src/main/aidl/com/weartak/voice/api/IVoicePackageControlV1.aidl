package com.weartak.voice.api;
import android.os.Bundle;
import com.weartak.voice.api.IVoicePackageReceiverV1;
interface IVoicePackageControlV1 {
 Bundle getCapabilities();
 Bundle registerReceiver(IVoicePackageReceiverV1 receiver);
 Bundle takePackage(String handle);
 Bundle reportState(String transferId, String payloadSha256, String missionSha256, int state, int errorCode);
 Bundle getStatus(String transferId);
 void unregisterReceiver(IVoicePackageReceiverV1 receiver);
}
