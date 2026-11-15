package com.weartak.plugin.sdk.community.v1;

import android.os.Bundle;

interface ICommunityIdentityControlV1 {
    int getVersion();
    Bundle readCommunityIdentity();
}
