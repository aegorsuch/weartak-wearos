// This class is apart o

#pragma once

#include <chrono>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <span>
#include <string_view>
#include <vector>

namespace wearvpn {

using StreamId = std::uint64_t;

enum class TunnelState : std::int32_t {
    Stopped = 0,
    Connecting = 1,
    Connected = 2,
    Stopping = 3,
    Error = 4
};

enum class SessionError : std::int32_t {
    None = 0,
    InvalidArgument = 1,
    InvalidProfile = 2,
    UnsupportedAuthentication = 3,
    NotConnected = 4,
    AlreadyRunning = 5,
    Stopping = 6,
    Timeout = 7,
    QueueFull = 8,
    StreamNotFound = 9,
    StreamClosed = 10,
    NetworkFailure = 11,
    InternalError = 12
};

struct StreamOpenResult {
    StreamId id = 0;
    SessionError error = SessionError::None;
};

struct StreamWriteResult {
    std::size_t accepted = 0;
    SessionError error = SessionError::None;
};

enum class StreamReadStatus : std::int32_t {
    Data = 0,
    Timeout = 1,
    Eof = 2,
    Error = 3
};

struct StreamReadResult {
    StreamReadStatus status = StreamReadStatus::Error;
    SessionError error = SessionError::InternalError;
    std::vector<std::uint8_t> bytes;
};

// One live instance owns this process's lwIP stack.
//
// Public calls may run concurrently while the object remains alive.
// Before destruction, the owner must finish/join all public callers.
class NativeTunnelSession final {
public:
    // May throw on allocation/thread failure or a second live session.
    NativeTunnelSession();

    // Waits for the worker and OpenVPN connection thread to finish.
    // There is no guaranteed deadline for destruction.
    ~NativeTunnelSession();

    NativeTunnelSession(const NativeTunnelSession&) = delete;
    NativeTunnelSession& operator=(const NativeTunnelSession&) = delete;

    static SessionError validateProfile(
        std::span<const std::uint8_t> profile
    ) noexcept;

    // Copies profile bytes.
    //
    // None means the transport accepted the start. Use awaitConnected()
    // to wait for the actual tunnel connection.
    SessionError start(
        std::span<const std::uint8_t> profile
    ) noexcept;

    // Timeouts must be positive and at most 60 seconds.
    //
    // Timing out here does not stop an otherwise connecting tunnel.
    SessionError awaitConnected(
        std::chrono::milliseconds timeout
    ) noexcept;

    TunnelState state() const noexcept;

    // Asynchronous and idempotent.
    //
    // None acknowledges the stop request. Stopped/Error is published
    // only after transport reap succeeds.
    SessionError stop() noexcept;

    StreamOpenResult openStream(
        std::string_view ipv4,
        std::uint16_t port,
        std::chrono::milliseconds timeout
    ) noexcept;

    // At most 64 KiB per call; empty writes and handle zero are invalid.
    //
    // accepted counts bytes copied into lwIP, not bytes acknowledged
    // by the peer. Retry only the unaccepted suffix.
    StreamWriteResult writeStream(
        StreamId id,
        std::span<const std::uint8_t> bytes,
        std::chrono::milliseconds timeout
    ) noexcept;

    // maxBytes must be 1..64 KiB.
    // The caller owns the returned bytes.
    StreamReadResult readStream(
        StreamId id,
        std::size_t maxBytes,
        std::chrono::milliseconds timeout
    ) noexcept;

    // Asynchronous and idempotent for unknown/old positive IDs; zero is invalid.
    SessionError closeStream(StreamId id) noexcept;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace wearvpn