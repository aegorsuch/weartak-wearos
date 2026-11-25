package com.weartak.voice.api;
interface IVoiceRequestSigner {
    byte[] sign(in byte[] certificationRequestInfo);
}
