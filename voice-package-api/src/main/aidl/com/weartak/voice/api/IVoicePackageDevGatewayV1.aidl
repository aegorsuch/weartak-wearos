package com.weartak.voice.api;
import android.os.Bundle;
import com.weartak.voice.api.IVoicePackageControlV1;
interface IVoicePackageDevGatewayV1 {
 void initialize(String hostPackage, int hostUid, IVoicePackageControlV1 control);
 Bundle getState();
 void shutdown();
}
