// WEAR-590 Ticket 1: Kotlin/Java -> NativeTunnelSession.
//
// Reference: WearVPN's native-lib.cpp. This version is only a marshaller:
// validate arguments, make bounded copies, call the session, map results.
// No lwIP calls, pointer handles, packet queues or extra worker live here.
// JNI calls run on their calling JVM thread. No JNIEnv*, Java array or
// borrowed string is ever handed to the session's networking worker.

#include "NativeTunnelSession.h"
#include "OpenVpnTransport.h"

#include <jni.h>
#include <array>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <new>
#include <string_view>
#include <utility>
#include <vector>
#include <arpa/inet.h>

namespace {
using wearvpn::NativeTunnelSession;
using wearvpn::SessionError;
using wearvpn::StreamId;
using wearvpn::StreamReadStatus;
using wearvpn::TunnelState;

// Match OpenVpnNativeBridge.kt. State codes are a separate value domain:
// read result 0 means EOF; state 0 means STOPPED.
constexpr jint kOk = 0;
constexpr jint kInvalidArgument = -1;
constexpr jint kInvalidProfile = -2;
constexpr jint kUnsupportedAuthentication = -3;
constexpr jint kNotConnected = -4;
constexpr jint kAlreadyRunning = -5;
constexpr jint kStopping = -6;
constexpr jint kTimeout = -7;
constexpr jint kQueueFull = -8;
constexpr jint kStreamNotFound = -9;
constexpr jint kStreamClosed = -10;
constexpr jint kNetworkFailure = -11;
constexpr jint kInternalError = -12;
constexpr jint kErrorState = 4;
constexpr jint kMaxIoBytes = 64 * 1024;
constexpr jlong kMaxTimeoutMs = 60000;

// Explicit mapping protects the external ABI if a C++ enum changes later.
jint errorCode(SessionError error) noexcept {
    switch (error) {
    case SessionError::None: return kOk;
    case SessionError::InvalidArgument: return kInvalidArgument;
    case SessionError::InvalidProfile: return kInvalidProfile;
    case SessionError::UnsupportedAuthentication: return kUnsupportedAuthentication;
    case SessionError::NotConnected: return kNotConnected;
    case SessionError::AlreadyRunning: return kAlreadyRunning;
    case SessionError::Stopping: return kStopping;
    case SessionError::Timeout: return kTimeout;
    case SessionError::QueueFull: return kQueueFull;
    case SessionError::StreamNotFound: return kStreamNotFound;
    case SessionError::StreamClosed: return kStreamClosed;
    case SessionError::NetworkFailure: return kNetworkFailure;
    case SessionError::InternalError: return kInternalError;
    }
    return kInternalError;
}

jint stateCode(TunnelState state) noexcept {
    switch (state) {
    case TunnelState::Stopped: return 0;
    case TunnelState::Connecting: return 1;
    case TunnelState::Connected: return 2;
    case TunnelState::Stopping: return 3;
    case TunnelState::Error: return kErrorState;
    }
    return kErrorState;
}

// Wipe live bytes before vector destruction, including exception unwinding.
// This covers JNI-owned storage only. Callers still own their Java arrays;
// OpenVPN/lwIP's internal copies follow their own lifetime/cleanup policies.
void wipe(std::vector<std::uint8_t>& bytes) noexcept {
    volatile std::uint8_t* destination = bytes.data();
    for (std::size_t count = bytes.size(); count != 0; --count) {
        *destination++ = 0;
    }
}

struct ScopedBytes final {
    std::vector<std::uint8_t> bytes;
    explicit ScopedBytes(std::size_t size) : bytes(size) {}
    explicit ScopedBytes(std::vector<std::uint8_t>&& source) noexcept
        : bytes(std::move(source)) {}
    ~ScopedBytes() { wipe(bytes); }
    ScopedBytes(const ScopedBytes&) = delete;
    ScopedBytes& operator=(const ScopedBytes&) = delete;
};

// Initialization is thread-safe; the session owns actual concurrency.
// validateProfile() does not create the singleton. Its lifetime belongs to
// the loaded library/process, not an Activity. Its destructor may wait for
// OpenVPN: request stop and observe STOPPED during normal shutdown. Never
// dynamically unload the library while native calls are still in flight.
NativeTunnelSession& session() {
    static NativeTunnelSession instance;
    return instance;
}

// EVERY exported function executes its body inside this exception boundary,
// including first-time session construction. Do not clear a pending Java
// exception: the JVM propagates it instead of using our numeric return.
// Never log exception text, which could include configuration secrets.
template<class Result, class Function>
Result boundary(JNIEnv* env, Result fallback, Function&& body) noexcept {
    try {
        if (env == nullptr || env->ExceptionCheck()) {
            return fallback;
        }
        return std::forward<Function>(body)();
    } catch (const std::bad_alloc&) {
        return fallback;
    } catch (...) {
        return fallback;
    }
}

bool validTimeout(jlong milliseconds) noexcept {
    return milliseconds >= 1 && milliseconds <= kMaxTimeoutMs;
}

std::chrono::milliseconds timeout(jlong milliseconds) noexcept {
    // Only called after validation, so conversion cannot overflow.
    return std::chrono::milliseconds{static_cast<std::int64_t>(milliseconds)};
}

jint validateSlice(JNIEnv* env, jbyteArray array, jint offset, jint length) {
    if (array == nullptr || offset < 0 || length <= 0 || length > kMaxIoBytes) {
        return kInvalidArgument;
    }
    const jsize size = env->GetArrayLength(array);
    if (env->ExceptionCheck()) {
        return kInternalError;
    }
    // Avoid overflow in offset + length by checking the remaining space.
    if (offset > size || length > size - offset) {
        return kInvalidArgument;
    }
    return kOk;
}

// Literal IPv4 needs at most 15 ASCII characters. Copy bounded UTF-16 code
// units to stack storage and require digits/dots before inet_pton parsing.
// This avoids modified-UTF-8 surprises and GetStringUTFChars ownership.
// Nothing is pinned or borrowed, so no string-release operation is needed.
jint copyIpv4(JNIEnv* env, jstring value,
              std::array<char, INET_ADDRSTRLEN>& destination) {
    if (value == nullptr) {
        return kInvalidArgument;
    }
    const jsize length = env->GetStringLength(value);
    if (env->ExceptionCheck()) {
        return kInternalError;
    }
    if (length <= 0 || length >= INET_ADDRSTRLEN) {
        return kInvalidArgument;
    }
    std::array<jchar, INET_ADDRSTRLEN> characters{};
    env->GetStringRegion(value, 0, length, characters.data());
    if (env->ExceptionCheck()) {
        return kInternalError;
    }
    for (jsize index = 0; index < length; ++index) {
        const auto character = characters[static_cast<std::size_t>(index)];
        if (character != '.' && (character < '0' || character > '9')) {
            return kInvalidArgument;
        }
        destination[static_cast<std::size_t>(index)] = static_cast<char>(character);
    }
    in_addr parsed{};
    return inet_pton(AF_INET, destination.data(), &parsed) == 1
        ? kOk : kInvalidArgument;
}

// Reject profile size before allocation. Copy without pinning a Java array;
// session.start() copies/evaluates synchronously before our copy is wiped.
jint profileOperation(JNIEnv* env, jbyteArray profile, bool start) {
    if (profile == nullptr) {
        return kInvalidArgument;
    }
    const jsize length = env->GetArrayLength(profile);
    if (env->ExceptionCheck()) {
        return kInternalError;
    }
    if (length <= 0 || static_cast<std::size_t>(length) >
                           wearvpn::OpenVpnTransport::MaxProfileBytes) {
        return kInvalidProfile;
    }
    ScopedBytes copy(static_cast<std::size_t>(length));
    env->GetByteArrayRegion(profile, 0, length,
                           reinterpret_cast<jbyte*>(copy.bytes.data()));
    if (env->ExceptionCheck()) {
        return kInternalError;
    }
    return errorCode(start ? session().start(copy.bytes)
                          : NativeTunnelSession::validateProfile(copy.bytes));
}
} // namespace

// These are INSTANCE methods on a Kotlin object (no @JvmStatic on external
// declarations): receive jobject, not jclass. The package's underscore is
// escaped as _1 by conventional JNI naming. Keep all nine names synchronized.

extern "C" JNIEXPORT jint JNICALL
Java_com_aitech_weartak_1civ_service_openvpn_OpenVpnNativeBridge_nativeValidateProfile(
    JNIEnv* env, jobject, jbyteArray profile) noexcept {
    return boundary(env, kInternalError, [&]() -> jint {
        return profileOperation(env, profile, false);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aitech_weartak_1civ_service_openvpn_OpenVpnNativeBridge_nativeStart(
    JNIEnv* env, jobject, jbyteArray profile) noexcept {
    return boundary(env, kInternalError, [&]() -> jint {
        return profileOperation(env, profile, true);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aitech_weartak_1civ_service_openvpn_OpenVpnNativeBridge_nativeAwaitConnected(
    JNIEnv* env, jobject, jlong timeoutMs) noexcept {
    return boundary(env, kInternalError, [&]() -> jint {
        if (!validTimeout(timeoutMs)) {
            return kInvalidArgument;
        }
        return errorCode(session().awaitConnected(timeout(timeoutMs)));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aitech_weartak_1civ_service_openvpn_OpenVpnNativeBridge_nativeGetState(
    JNIEnv* env, jobject) noexcept {
    // The state domain is 0..4, never negative error codes. A failure to
    // construct/query the session maps to ERROR (4).
    return boundary(env, kErrorState, [&]() -> jint {
        return stateCode(session().state());
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aitech_weartak_1civ_service_openvpn_OpenVpnNativeBridge_nativeStop(
    JNIEnv* env, jobject) noexcept {
    return boundary(env, kInternalError, [&]() -> jint {
        return errorCode(session().stop());
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_aitech_weartak_1civ_service_openvpn_OpenVpnNativeBridge_nativeOpenStream(
    JNIEnv* env, jobject, jstring ipv4, jint port, jlong timeoutMs) noexcept {
    return boundary(env, static_cast<jlong>(kInternalError), [&]() -> jlong {
        if (port < 1 || port > 65535 || !validTimeout(timeoutMs)) {
            return kInvalidArgument;
        }
        std::array<char, INET_ADDRSTRLEN> address{};
        const auto validation = copyIpv4(env, ipv4, address);
        if (validation != kOk) {
            return validation;
        }
        const auto result = session().openStream(
            std::string_view{address.data()}, static_cast<std::uint16_t>(port),
            timeout(timeoutMs));
        if (result.error != SessionError::None) {
            return errorCode(result.error);
        }
        if (result.id == 0 || result.id >
                static_cast<StreamId>(std::numeric_limits<jlong>::max())) {
            // Do not leak a connection whose handle cannot cross this ABI.
            if (result.id != 0) {
                session().closeStream(result.id);
            }
            return kInternalError;
        }
        return static_cast<jlong>(result.id);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aitech_weartak_1civ_service_openvpn_OpenVpnNativeBridge_nativeWriteStream(
    JNIEnv* env, jobject, jlong streamId, jbyteArray source,
    jint offset, jint length, jlong timeoutMs) noexcept {
    return boundary(env, kInternalError, [&]() -> jint {
        if (streamId <= 0 || !validTimeout(timeoutMs)) {
            return kInvalidArgument;
        }
        const auto validation = validateSlice(env, source, offset, length);
        if (validation != kOk) {
            return validation;
        }
        ScopedBytes copy(static_cast<std::size_t>(length));
        env->GetByteArrayRegion(source, offset, length,
                               reinterpret_cast<jbyte*>(copy.bytes.data()));
        if (env->ExceptionCheck()) {
            return kInternalError;
        }
        const auto id = static_cast<StreamId>(streamId);
        const auto result = session().writeStream(id, copy.bytes, timeout(timeoutMs));
        if (result.accepted > static_cast<std::size_t>(length)) {
            session().closeStream(id);
            return kInternalError;
        }
        // Preserve partial acceptance even when a timeout/error coincides.
        // Otherwise a caller could resend bytes that lwIP already accepted.
        // This count says nothing about peer delivery or acknowledgement.
        if (result.accepted != 0) {
            return static_cast<jint>(result.accepted);
        }
        return result.error == SessionError::None
            ? kInternalError : errorCode(result.error);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aitech_weartak_1civ_service_openvpn_OpenVpnNativeBridge_nativeReadStream(
    JNIEnv* env, jobject, jlong streamId, jbyteArray destination,
    jint offset, jint length, jlong timeoutMs) noexcept {
    return boundary(env, kInternalError, [&]() -> jint {
        if (streamId <= 0 || !validTimeout(timeoutMs)) {
            return kInvalidArgument;
        }
        const auto validation = validateSlice(env, destination, offset, length);
        if (validation != kOk) {
            return validation;
        }
        const auto id = static_cast<StreamId>(streamId);
        auto result = session().readStream(
            id, static_cast<std::size_t>(length), timeout(timeoutMs));
        ScopedBytes copy(std::move(result.bytes));

        switch (result.status) {
        case StreamReadStatus::Data:
            if (result.error != SessionError::None || copy.bytes.empty() ||
                copy.bytes.size() > static_cast<std::size_t>(length)) {
                session().closeStream(id);
                return kInternalError;
            }
            env->SetByteArrayRegion(destination, offset,
                                   static_cast<jsize>(copy.bytes.size()),
                                   reinterpret_cast<const jbyte*>(copy.bytes.data()));
            if (env->ExceptionCheck()) {
                // Native bytes have been consumed. Fail/close this stream
                // instead of permitting a retry that silently skips them.
                // No more JNI calls: preserve the pending Java exception.
                session().closeStream(id);
                return kInternalError;
            }
            return static_cast<jint>(copy.bytes.size());
        case StreamReadStatus::Timeout:
            return kTimeout;
        case StreamReadStatus::Eof:
            return kOk;
        case StreamReadStatus::Error:
            return result.error == SessionError::None
                ? kInternalError : errorCode(result.error);
        }
        return kInternalError;
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aitech_weartak_1civ_service_openvpn_OpenVpnNativeBridge_nativeCloseStream(
    JNIEnv* env, jobject, jlong streamId) noexcept {
    return boundary(env, kInternalError, [&]() -> jint {
        if (streamId <= 0) {
            return kInvalidArgument;
        }
        // Unknown positive IDs close harmlessly; reads/writes reject them.
        return errorCode(session().closeStream(static_cast<StreamId>(streamId)));
    });
}
