package com.weartak.voice.api;
/** Optional versioned Binder extension. No frozen WearTAK SDK parcels or transactions change. */
public final class VoicePackageContract {
 private VoicePackageContract() {}
 public static final int VERSION=1, MAX_ZIP=262144, MAX_JSON=32700;
 public static final String EXTRA_VERSION="com.weartak.voice.package.version";
 public static final String EXTRA_CONTROL="com.weartak.voice.package.control";
 public static final String DEV_PERMISSION="se.takcampus.voice.permission.BIND_VOICE_PACKAGE_DEV";
 public static final int JSON=1, ZIP=2;
 public static final int DOWNLOADED=1, STAGED=2, APPLIED=3, REJECTED=4, FAILED=5, EXPIRED=6;
 public static final int OK=0, UNAUTHORIZED=1, INVALID=2, TOO_LARGE=3, UNAVAILABLE=4, BUSY=5, INVALID_PACKAGE=6, LOCAL_IO=7;
 public static String stateName(int state) { return switch(state) {
  case DOWNLOADED -> "DOWNLOADED"; case STAGED -> "STAGED"; case APPLIED -> "APPLIED";
  case REJECTED -> "REJECTED"; case FAILED -> "FAILED"; case EXPIRED -> "EXPIRED"; default -> "UNKNOWN"; }; }
}
