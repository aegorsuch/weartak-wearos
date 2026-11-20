package com.weartak.plugin.sdk.ditto.v1;

import android.os.IBinder;
import com.weartak.plugin.sdk.ditto.v1.DittoLeaseResultV1;
import com.weartak.plugin.sdk.ditto.v1.IDittoHostControlV1;

interface IDittoOwnershipV1 {
    DittoLeaseResultV1 acquire(in IBinder session, IDittoHostControlV1 host);
    DittoLeaseResultV1 release(in IBinder session, long generation);
}
