package com.weartak.voice.api;
import android.os.Bundle;
oneway interface IVoicePackageReceiverV1 {
 void onOffer(in Bundle offer);
 void onRevoked(String handle, int reason);
}
