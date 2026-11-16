#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <cstddef>
#include <mutex>
#include <span>
#include <thread>

// Forward declarations for OpenVPN3 classes used in this header.
namespace openvpn {
    class Stop;
}

namespace wearvpn {

    class WearOpenVpnClient;
//numeric representation of an IPv4 address, gateway, and prefix length
    struct TunnelAddress {
        std::uint32_t ipv4 = 0;
        std::uint32_t gateway = 0;

        std::uint8_t prefixLength = 0;
        std::uint16_t mtu = 1500;
        bool net30 = false;
};

// The following enums are used to communicate OpenVPN connection status
enum class TransportError {
    None,
    InvalidArgument,
    InvalidProfile,
    UnsupportedAuthentication,
    AlreadyRunning,
    SocketSetupFailed,
    StartFailed,
    InvalidTunnelConfiguration,
    AuthenticationFailed,
    ConnectionTimeout,
    ConnectionFailed,
    Disconnected,
    ReconnectionRequired,
    EventQueueFull,
    InternalError
};

enum class TransportEventType {
    Connected,
    Disconnected,
    Failed,
    Exited
};

// This struct is used to communicate OpenVPN connection events
struct TransportEvent {
    std::uint64_t generation = 0;
    TransportEventType type = TransportEventType::Failed;
    TransportError error = TransportError::None;
    TunnelAddress address{};

};

// This enum is used to communicate the state of the transport layer
enum class PacketIoResult {
    Complete,
    WouldBlock,
    Closed,
    Error
};


// Owns one OpenVPN client, its stop token, and its connection thread.
//
// A single owner thread calls every public instance method and destroys this
// object. In Phase 3, that owner will be the native network worker. The
// OpenVPN connection thread runs connect() and delivers callbacks; callbacks
// only enqueue events and never call lwIP.
//
// threadFinished_ communicates completion between the two threads. The event
// mutex protects the event queue. All other transport state belongs to the
// owner thread. Do not destroy this object on the OpenVPN connection thread.
//
// Normal shutdown is: requestStop(), drain takeEvent(), wait until
// connectionThreadFinished(), then reap(). Destroy only after reaping.
class OpenVpnTransport final {
public:
    static constexpr std::size_t MaxProfileBytes = 256 * 1024;
    static constexpr std::size_t MaxIpPacketBytes = 65535;
    static constexpr std::size_t MaxQueuedEvents = 32;
//
    OpenVpnTransport();
    ~OpenVpnTransport();
    // Disallow copy and assignment to prevent resource management issues.
    OpenVpnTransport(const OpenVpnTransport&) = delete;
    OpenVpnTransport& operator=(const OpenVpnTransport&) = delete;

    static TransportError validateProfile(
        std::span<const std::uint8_t> profile
    ) noexcept;

    TransportError start(
        std::span<const std::uint8_t> profile,
        std::uint64_t generation
    ) noexcept;

    void requestStop() noexcept;

    bool connectionThreadFinished() const noexcept;
    bool hasClient() const noexcept;

     // Returns false while the connection thread is running or events remain
    // unconsumed. Otherwise joins the finished thread, destroys the client
    // and stop token, resets this attempt, and returns true.
    // The owner must call takeEvent() to drain events before retrying reap().
    bool reap() noexcept;

     // Returns one fixed, generation-matched event, or false if none is ready.
    // The owner interprets Disconnected/Exited using its own state: an event
    // during an intentional stop is not automatically an application failure
    bool takeEvent(TransportEvent& event) noexcept;
    // Returns true if the event was successfully taken, false if the queue is empty.
    PacketIoResult sendIpPacket(
        std::span<const std::uint8_t> packet
    ) noexcept;

    PacketIoResult receiveIpPacket(
        std::span<std::uint8_t> storage,
        std::size_t& received
    ) noexcept;


private:
// Called by the OpenVPN connection thread to report events.
    void postEvent(TransportEvent event) noexcept;
    void clearEvents() noexcept;

    // Must outlive the client that references it.
    std::unique_ptr<openvpn::Stop> stopToken_;

    std::unique_ptr<WearOpenVpnClient> client_;
    std::thread connectionThread_;

    // Written by the connection thread; observed by the worker.
    std::atomic<bool> threadFinished_{true};

    // Accessed only by the native network worker.
    std::uint64_t generation_ = 0;
    bool stopping_ = false;
    bool connected_ = false;

    // Callback producer and worker consumer use eventMutex_.
    std::mutex eventMutex_;
    std::array<TransportEvent, MaxQueuedEvents> events_{};
    std::size_t eventHead_ = 0;
    std::size_t eventCount_ = 0;
    bool eventOverflow_ = false;
};

} // namespace wearvpn