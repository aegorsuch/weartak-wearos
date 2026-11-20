package com.weartak.plugin.sdk.ditto.v1;

import com.weartak.plugin.sdk.ditto.v1.DittoStatusV1;
import com.weartak.plugin.sdk.ditto.v1.IDittoOwnershipV1;

interface IDittoDiscoveryV1 {
    DittoStatusV1 getStatus();
    @nullable IDittoOwnershipV1 getOwnership();
}
