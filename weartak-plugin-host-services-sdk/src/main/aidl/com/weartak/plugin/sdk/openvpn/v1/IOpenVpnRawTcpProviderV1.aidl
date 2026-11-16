package com.weartak.plugin.sdk.openvpn.v1;

interface IOpenVpnRawTcpProviderV1 {
    int getApiVersion();
    int getRuntimeState(String profileId);
    long openStream(
        String serverId,
        String profileId,
        String endpointAddress,
        int port,
        long connectionGeneration,
        long timeoutMs
    );
    int readStream(
        String profileId,
        long streamId,
        inout byte[] destination,
        int offset,
        int length,
        long timeoutMs
    );
    int writeStream(
        String profileId,
        long streamId,
        in byte[] source,
        int offset,
        int length,
        long timeoutMs
    );
    int closeStream(String profileId, long streamId);
}
