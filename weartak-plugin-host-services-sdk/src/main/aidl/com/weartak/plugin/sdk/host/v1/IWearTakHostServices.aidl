package com.weartak.plugin.sdk.host.v1;

import android.os.Bundle;

// Optional extension discovered through PluginInitParcel.hostExtras.
interface IWearTakHostServices {
    Bundle getCapabilities();
    Bundle requestMapAction(in Bundle request);
    Bundle requestTakServer(in Bundle request);
}
