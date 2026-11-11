#include "OpenVpnTransport.h"
#include "WearOpenVpnClient.hpp"

#include <cerrno>
#include <string>

#include <sys/socket.h>
#include <sys/uio.h>

#include <mbedtls/platform_util.h>
#include <openvpn/common/stop.hpp>
#include <openvpn/common/unicode.hpp>

namespace wearvpn {

namespace {
void wipeString(std::string& text) noexcept {
    if (!text.empty()) {
        mbedtls_platform_zeroize(text.data(), text.size());
    }

    text.clear();
}

// Wipes temporary configuration strings owned by this wrapper.
// Caller buffers and OpenVPN-owned parsed state have separate lifetimes.
struct ScopedConfig final {
    openvpn::ClientAPI::Config value;

    ~ScopedConfig() {
        wipeString(value.content);
        wipeString(value.privateKeyPassword);

        for (auto& kv : value.contentList) {
            wipeString(kv.key);
            wipeString(kv.value);
        }
    }
};

// The profile merge API also returns a temporary profile copy.
struct ScopedMerge final {
    openvpn::ClientAPI::MergeConfig value;

    ~ScopedMerge() {
        wipeString(value.profileContent);
        wipeString(value.errorText);
    }
};


bool profileShapeValid(
    std::span<const std::uint8_t> profile
) noexcept {
    if (profile.empty() ||
        profile.size() > OpenVpnTransport::MaxProfileBytes) {
        return false;
    }

    // The pinned helper rejects malformed UTF-8 and embedded NUL bytes.
    return openvpn::Unicode::is_valid_utf8_uchar_buf(
        reinterpret_cast<const unsigned char*>(profile.data()),
        profile.size()
    );

}


// Populate a Config with the given profile. The profile is copied into
TransportError populateConfig(
    ScopedConfig& config,
    std::span<const std::uint8_t> profile
) {
    config.value.content.assign(
        reinterpret_cast<const char*>(profile.data()),
        profile.size()
    );

    openvpn::ClientAPI::OpenVPNClientHelper helper;


    ScopedMerge merged{
        helper.merge_config_string(config.value.content)
    };

    if (merged.value.status != "MERGE_SUCCESS") {
        return TransportError::InvalidProfile;
    }

    // Evaluate the normalized profile. The original temporary copy moves
    // into merged and is wiped when this function returns.
    config.value.content.swap(merged.value.profileContent);

    config.value.guiVersion = "WearTAK";
    config.value.connTimeout = 30;
    config.value.tunPersist = false;
    config.value.retryOnAuthFailed = false;
    config.value.synchronousDnsLookup = false;
    config.value.sslDebugLevel = 0;

    return TransportError::None;
}


// Evaluate the result of eval_config() and determine if the profile is usable.
TransportError evaluateResult(
    const openvpn::ClientAPI::EvalConfig& result
) noexcept {
    if (result.error) {
        return TransportError::InvalidProfile;
    }

    if (!result.autologin ||
        result.externalPki ||
        result.privateKeyPasswordRequired ||
        !result.staticChallenge.empty()) {
        return TransportError::UnsupportedAuthentication;
    }

    return TransportError::None;
}

// Convert a POSIX socket error into a PacketIoResult.
PacketIoResult socketFailure(int error) noexcept {
    if (error == EAGAIN ||
        error == EWOULDBLOCK ||
        error == EINTR ||
        error == ENOBUFS) {
        return PacketIoResult::WouldBlock;
    }

    if (error == EBADF ||
        error == ENOTCONN ||
        error == EPIPE ||
        error == ECONNRESET) {
        return PacketIoResult::Closed;
    }

    return PacketIoResult::Error;
}

bool ipv4PacketShapeValid(
    std::span<const std::uint8_t> packet
) noexcept {
    if (packet.size() < 20 ||
        packet.size() > OpenVpnTransport::MaxIpPacketBytes) {
        return false;
    }

    if ((packet[0] >> 4) != 4) {
        return false;
    }

    const std::size_t headerLength =
        static_cast<std::size_t>(packet[0] & 0x0F) * 4;

    const std::size_t totalLength =
        (static_cast<std::size_t>(packet[2]) << 8) |
        static_cast<std::size_t>(packet[3]);

    return headerLength >= 20 &&
        headerLength <= packet.size() &&
        totalLength == packet.size();
}

} // namespace

OpenVpnTransport::OpenVpnTransport() = default;

OpenVpnTransport::~OpenVpnTransport() {
    requestStop();

    // Cleanup fallback: destruction may block until connect() returns.
    // The owner must never destroy this object on its connection thread.
    if (connectionThread_.joinable()) {
        connectionThread_.join();
    }

    client_.reset();
    stopToken_.reset();
}

TransportError OpenVpnTransport::validateProfile(
    std::span<const std::uint8_t> profile
) noexcept {
    if (!profileShapeValid(profile)) {
        return TransportError::InvalidProfile;
    }

    try {
        ScopedConfig config;

        const auto preparation = populateConfig(config, profile);
        if (preparation != TransportError::None) {
            return preparation;
        }

        openvpn::ClientAPI::OpenVPNClientHelper helper;
        return evaluateResult(helper.eval_config(config.value));
    } catch (...) {
        return TransportError::InternalError;
    }
}

TransportError OpenVpnTransport::start(
    std::span<const std::uint8_t> profile,
    std::uint64_t generation
) noexcept {
    if (generation == 0) {
        return TransportError::InvalidArgument;
    }

    if (client_ || connectionThread_.joinable()) {
        return TransportError::AlreadyRunning;
    }

    if (!profileShapeValid(profile)) {
        return TransportError::InvalidProfile;
    }

    generation_ = generation;
    stopping_ = false;
    connected_ = false;
    clearEvents();

    // This cleanup is used only before a thread has been launched.
    const auto fail = [this](TransportError error) noexcept {
        client_.reset();
        stopToken_.reset();

        generation_ = 0;
        stopping_ = false;
        connected_ = false;

        threadFinished_.store(true, std::memory_order_release);
        clearEvents();

        return error;
    };

    try {
        stopToken_ = std::make_unique<openvpn::Stop>();

        client_ = std::make_unique<WearOpenVpnClient>(
            *stopToken_,
            [this, generation](TransportEvent event) noexcept {
                event.generation = generation;
                postEvent(event);
            }
        );

        // Evaluate synchronously before launching the connection thread.
        {
            ScopedConfig config;

            const auto preparation = populateConfig(config, profile);
            if (preparation != TransportError::None) {
                return fail(preparation);
            }

            const auto evaluation =
                evaluateResult(client_->eval_config(config.value));

            if (evaluation != TransportError::None) {
                return fail(evaluation);
            }
        }

        if (!client_->initializeTunSocketPair()) {
            return fail(TransportError::SocketSetupFailed);
        }

        threadFinished_.store(false, std::memory_order_release);

        // The transport keeps this client alive until after join().
        WearOpenVpnClient* const client = client_.get();

        connectionThread_ = std::thread(
            [this, client, generation]() {
                TransportError error = TransportError::None;

                try {
                    const auto status = client->connect();

                    if (status.error) {
                        error = TransportError::ConnectionFailed;
                    }
                } catch (...) {
                    error = TransportError::InternalError;
                }

                TransportEvent event;
                event.generation = generation;
                event.type = TransportEventType::Exited;
                event.error = error;
                postEvent(event);

                // No further events are posted by this thread after this.
                threadFinished_.store(
                    true,
                    std::memory_order_release
                );
            }
        );

        return TransportError::None;
    } catch (...) {
        return fail(TransportError::StartFailed);
    }
}

void OpenVpnTransport::requestStop() noexcept {
    connected_ = false;

    if (stopping_) {
        return;
    }

    stopping_ = true;

    if (stopToken_) {
        stopToken_->stop();
    }
}

bool OpenVpnTransport::connectionThreadFinished() const noexcept {
    return threadFinished_.load(std::memory_order_acquire);
}

bool OpenVpnTransport::hasClient() const noexcept {
    return static_cast<bool>(client_);
}

bool OpenVpnTransport::reap() noexcept {
    if (!connectionThreadFinished()) {
        return false;
    }

    // Once completion is observed, the connection thread will post
    // no more events. Require the worker to consume pending events.
    {
        std::lock_guard<std::mutex> lock(eventMutex_);

        if (eventCount_ != 0 || eventOverflow_) {
            return false;
        }
    }

    if (connectionThread_.joinable()) {
        connectionThread_.join();
    }

    client_.reset();
    stopToken_.reset();

    generation_ = 0;
    stopping_ = false;
    connected_ = false;

    clearEvents();
    return true;
}

void OpenVpnTransport::postEvent(TransportEvent event) noexcept {
    std::lock_guard<std::mutex> lock(eventMutex_);

    // Once overflow has occurred, the worker must first observe failure.
    if (eventOverflow_) {
        return;
    }

    if (eventCount_ == events_.size()) {
        eventOverflow_ = true;
        return;
    }

    const std::size_t tail =
        (eventHead_ + eventCount_) % events_.size();

    events_[tail] = event;
    ++eventCount_;
}

void OpenVpnTransport::clearEvents() noexcept {
    std::lock_guard<std::mutex> lock(eventMutex_);

    events_.fill(TransportEvent{});
    eventHead_ = 0;
    eventCount_ = 0;
    eventOverflow_ = false;
}

bool OpenVpnTransport::takeEvent(TransportEvent& event) noexcept {
    // Bound the amount of filtering performed by one call.
    for (std::size_t inspected = 0;
         inspected < MaxQueuedEvents;
         ++inspected) {
        TransportEvent next;

        {
            std::lock_guard<std::mutex> lock(eventMutex_);

            if (eventOverflow_) {
                // The queue is no longer a trustworthy lifecycle history.
                events_.fill(TransportEvent{});
                eventHead_ = 0;
                eventCount_ = 0;
                eventOverflow_ = false;

                next.generation = generation_;
                next.type = TransportEventType::Failed;
                next.error = TransportError::EventQueueFull;
            } else {
                if (eventCount_ == 0) {
                    return false;
                }

                next = events_[eventHead_];
                events_[eventHead_] = TransportEvent{};

                eventHead_ = (eventHead_ + 1) % events_.size();
                --eventCount_;
            }
        }

        // Never apply a notification from another attempt.
        if (next.generation != generation_) {
            continue;
        }

        switch (next.type) {
        case TransportEventType::Connected:
            // A queued connection event cannot revive a stopped attempt.
            if (stopping_ || connectionThreadFinished()) {
                continue;
            }

            connected_ = true;
            break;

        case TransportEventType::Disconnected:
        case TransportEventType::Failed:
            requestStop();
            break;

        case TransportEventType::Exited:
            connected_ = false;
            stopping_ = true;
            break;
        }

        event = next;
        return true;
    }

    return false;
}

PacketIoResult OpenVpnTransport::sendIpPacket(
    std::span<const std::uint8_t> packet
) noexcept {
    if (!ipv4PacketShapeValid(packet)) {
        return PacketIoResult::Error;
    }

    if (!client_ || stopping_ || connectionThreadFinished()) {
        return PacketIoResult::Closed;
    }

    if (!connected_) {
        return PacketIoResult::WouldBlock;
    }

    const int fd = client_->appTunFd();
    if (fd < 0) {
        return PacketIoResult::Closed;
    }

    const ssize_t written = ::send(
        fd,
        packet.data(),
        packet.size(),
        MSG_DONTWAIT | MSG_NOSIGNAL
    );

    if (written < 0) {
        return socketFailure(errno);
    }

    if (static_cast<std::size_t>(written) != packet.size()) {
        // Never send the remainder as a second datagram.
        return PacketIoResult::Error;
    }

    return PacketIoResult::Complete;
}


PacketIoResult OpenVpnTransport::receiveIpPacket(
    std::span<std::uint8_t> storage,
    std::size_t& received
) noexcept {
    received = 0;

    // Require reusable storage large enough for any IPv4 datagram.
    if (storage.size() < MaxIpPacketBytes) {
        return PacketIoResult::Error;
    }

    if (!client_ || stopping_ || connectionThreadFinished()) {
        return PacketIoResult::Closed;
    }

    if (!connected_) {
        return PacketIoResult::WouldBlock;
    }

    const int fd = client_->appTunFd();
    if (fd < 0) {
        return PacketIoResult::Closed;
    }

    iovec buffer{};
    buffer.iov_base = storage.data();
    buffer.iov_len = MaxIpPacketBytes;

    msghdr message{};
    message.msg_iov = &buffer;
    message.msg_iovlen = 1;

    const ssize_t count = ::recvmsg(
        fd,
        &message,
        MSG_DONTWAIT
    );

    if (count < 0) {
        return socketFailure(errno);
    }

    if ((message.msg_flags & MSG_TRUNC) != 0) {
        return PacketIoResult::Error;
    }

    // A zero-length datagram is an invalid IP packet, not TCP EOF.
    if (count == 0) {
        return PacketIoResult::Error;
    }

    const auto size = static_cast<std::size_t>(count);
    if (size > MaxIpPacketBytes) {
        return PacketIoResult::Error;
    }

    const std::span<const std::uint8_t> packet{
        storage.data(),
        size
    };

    if (!ipv4PacketShapeValid(packet)) {
        return PacketIoResult::Error;
    }

    received = size;
    return PacketIoResult::Complete;
}

} // namespace wearvpn