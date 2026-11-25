package com.weartak.plugin.sdk.openvpn.v1;

import android.os.Bundle;
import com.weartak.plugin.sdk.openvpn.v1.IOpenVpnRawTcpProviderV1;

interface IWearTakOpenVpnHostServicesV1 {
    Bundle getCapabilities();
    Bundle getConfiguredServers();
    Bundle selectServerProfile(String serverId, String profileId);
    Bundle registerProvider(in IOpenVpnRawTcpProviderV1 provider);
    Bundle unregisterProvider();
    Bundle publishRuntimeState(String profileId, int state);
    Bundle clearLegacyProfiles();
}
