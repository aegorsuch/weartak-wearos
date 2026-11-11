#include "WearOpenVpnClient.hpp"

#include <arpa/inet.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cstdint>
#include <utility>


// Requested kernel socket-buffer sizes.
//
// The operating system may adjust these values. These are not an exact
// total-memory ceiling. This adapter adds no application packet queue.

namespace wearvpn {

namespace {

constexpr int kSocketBufferSize = 128 * 1024;

bool configureSocketBufferSize(int fd) noexcept {
    const int size = kSocketBufferSize;

    const bool sendConfigured =
        setsockopt(
            fd,
            SOL_SOCKET,
            SO_SNDBUF,
            &size,
            sizeof(size)
        ) == 0;

    const bool receiveConfigured =
        setsockopt(fd,
        SOL_SOCKET,
        SO_RCVBUF,
        &size,
        sizeof(size)
    ) == 0;

return sendConfigured && receiveConfigured;
}

}

WearOpenVpnClient::WearOpenVpnClient(
    openvpn::Stop& stopToken,
    EventHandler eventHandler
)
    : stopToken_(stopToken),
      eventHandler_(std::move(eventHandler)) {

}

WearOpenVpnClient::~WearOpenVpnClient() {
    // OpenVpnTransport must finish and join the connection thread
    // before destroying this object.
    closeTunFds();
}


bool WearOpenVpnClient::initializeTunSocketPair() noexcept {
    // Repeated initialization of an existing pair is harmless.
    if (tunOpenVpnFd_ >= 0 && tunAppFd_ >= 0) {
        return true;
    }

    int descriptors[2] = {-1, -1};

    // AF_UNIX: local communication on this device.
    // SOCK_DGRAM: preserve individual IP-packet boundaries.
    // SOCK_CLOEXEC: prevent unintended descriptor inheritance.
    if (socketpair(
            AF_UNIX,
            SOCK_DGRAM | SOCK_CLOEXEC,
            0,
            descriptors
        ) != 0) {
        return false;
    }

    if (!configureSocketBufferSize(descriptors[0]) ||
        !configureSocketBufferSize(descriptors[1])) {
        close(descriptors[0]);
        close(descriptors[1]);
        return false;
    }

    tunOpenVpnFd_ = descriptors[0];
    tunAppFd_ = descriptors[1];

    return true;
}

int WearOpenVpnClient::appTunFd() const noexcept {
    return tunAppFd_;
}

bool WearOpenVpnClient::tun_builder_new() {
    // This design requires a fresh client for a new tunnel after
    // an established connection is lost. Reusing the socketpair could
    // carry queued packets from the old session into the new one.
    if (established_) {
        return false;
    }

    address_ = TunnelAddress{};
    addressAssigned_ = false;
    layer_ = 3;

    return true;
}

bool WearOpenVpnClient::tun_builder_set_layer(int layer) {
    // Ticket 1 transports IP packets through lwIP.
    // Ethernet/TAP frames are not supported.
    if (layer != 3) {
        return false;
    }

    layer_ = layer;
    return true;
}

bool WearOpenVpnClient::tun_builder_set_remote_address(
    const std::string& /* address */,
    bool /* ipv6 */
) {
    // OpenVPN manages the outer connection to its VPN server.
    // This adapter does not install Android system routes.
    return true;
}

bool WearOpenVpnClient::tun_builder_add_address(
    const std::string& address,
    int prefixLength,
    const std::string& gateway,
    bool ipv6,
    bool net30
) {
    // A profile may provide both IPv4 and IPv6 metadata.
    // Only the IPv4 address is used by this Ticket 1 runtime.
    //
    // An IPv6-only profile will fail establishment because
    // addressAssigned_ will remain false.
    if (ipv6) {
        return true;
    }

    if (prefixLength < 0 || prefixLength > 32) {
        return false;
    }

    in_addr parsedAddress{};

    // Convert the address string into a numeric IPv4 address.
    if (inet_pton(
            AF_INET,
            address.c_str(),
            &parsedAddress
        ) != 1 ||
        parsedAddress.s_addr == 0) {
        return false;
    }

    in_addr parsedGateway{};

    // An omitted gateway remains zero.
    if (!gateway.empty() &&
        inet_pton(
            AF_INET,
            gateway.c_str(),
            &parsedGateway
        ) != 1) {
        return false;
    }

    // NativeTunnelSession owns one IPv4 interface.
    // Reject conflicting address assignments.
    if (addressAssigned_ &&
        address_.ipv4 != parsedAddress.s_addr) {
        return false;
    }

    // inet_pton returns addresses in network byte order.
    address_.ipv4 = parsedAddress.s_addr;
    address_.gateway = parsedGateway.s_addr;
    address_.prefixLength =
        static_cast<std::uint8_t>(prefixLength);
    address_.net30 = net30;

    addressAssigned_ = true;
    return true;
}

bool WearOpenVpnClient::tun_builder_add_route(
    const std::string& /* address */,
    int /* prefixLength */,
    int /* metric */,
    bool /* ipv6 */
) {
    // The application explicitly chooses which native streams use
    // this software tunnel. No device-wide routes are installed.
    return true;
}

bool WearOpenVpnClient::tun_builder_set_dns_options(
    const openvpn::DnsOptions& /* dns */
) {
    // Ticket 1's inner TCP API accepts literal IPv4 addresses.
    // It does not implement an inner VPN DNS resolver.
    return true;
}

bool WearOpenVpnClient::tun_builder_set_mtu(int mtu) {
    // Store the negotiated maximum IP-packet size.
    if (mtu < 576 || mtu > 65535) {
        return false;
    }

    address_.mtu = static_cast<std::uint16_t>(mtu);
    return true;
}

bool WearOpenVpnClient::tun_builder_set_session_name(
    const std::string& /* name */
) {
    // No session-name storage or logging is needed for this adapter.
    return true;
}

int WearOpenVpnClient::tun_builder_establish() {
    if (tunOpenVpnFd_ < 0 ||
        layer_ != 3 ||
        !addressAssigned_ ||
        established_) {
        return -1;
    }

    // The pinned Android OpenVPN implementation owns the descriptor
    // returned here. Give it a duplicate so it does not share ownership
    // of our original descriptor.
    const int duplicate = fcntl(
        tunOpenVpnFd_,
        F_DUPFD_CLOEXEC,
        0
    );

    if (duplicate < 0) {
        return -1;
    }

    established_ = true;
    return duplicate;
}

void WearOpenVpnClient::tun_builder_teardown(
    bool /* disconnect */
) {
    // Also occurs during intentional shutdown. NativeTunnelSession
    // must interpret this in the context of its current state.
    emit(
        TransportEventType::Disconnected,
        TransportError::Disconnected
    );
}

bool WearOpenVpnClient::socket_protect(
    openvpn_io::detail::socket_type /* socket */,
    std::string /* remote */,
    bool /* ipv6 */
) {
    // This adapter does not create an Android VpnService TUN or
    // redirect system traffic into itself. The outer OpenVPN socket
    // uses normal Android networking.
    //
    // Returning true does not perform an Android socket-protection
    // operation; this architecture does not require one here.
    return true;
}

bool WearOpenVpnClient::pause_on_connection_timeout() {
    // Report failure instead of leaving the connection paused.
    return false;
}

void WearOpenVpnClient::event(
    const openvpn::ClientAPI::Event& incoming
) {
    // Translate known events into fixed application values.
    // Never retain or log incoming.info.

    if (incoming.name == "AUTH_FAILED") {
        emit(
            TransportEventType::Failed,
            TransportError::AuthenticationFailed
        );
        return;
    }

    if (incoming.name == "CONNECTION_TIMEOUT") {
        emit(
            TransportEventType::Failed,
            TransportError::ConnectionTimeout
        );
        return;
    }

    if (incoming.error || incoming.fatal) {
        emit(
            TransportEventType::Failed,
            TransportError::ConnectionFailed
        );
        return;
    }

    if (incoming.name == "CONNECTED") {
        if (!addressAssigned_ || !established_) {
            emit(
                TransportEventType::Failed,
                TransportError::InvalidTunnelConfiguration
            );
            return;
        }

        emit(
            TransportEventType::Connected,
            TransportError::None
        );
        return;
    }

    if (incoming.name == "DISCONNECTED") {
        emit(
            TransportEventType::Disconnected,
            TransportError::Disconnected
        );
        return;
    }

    if (incoming.name == "RECONNECTING") {
        // This runtime requires cleanup and a fresh start.
        // Application-level retry policy belongs to TAKConnectService
        // when the later runtime integration is implemented.
        emit(
            TransportEventType::Failed,
            TransportError::ReconnectionRequired
        );
    }
}

void WearOpenVpnClient::acc_event(
    const openvpn::ClientAPI::AppCustomControlMessageEvent&
        /* event */
) {
    // No custom control-message consumer in this implementation.
    // Do not log its payload.
}

void WearOpenVpnClient::log(
    const openvpn::ClientAPI::LogInfo& /* info */
) {
    // Intentionally suppress arbitrary OpenVPN library log text.
    // Sanitized status is delivered through TransportEvent instead.
}


void WearOpenVpnClient::external_pki_cert_request(
    openvpn::ClientAPI::ExternalPKICertRequest& request
) {
    request.error = true;
    request.errorText = "EXTERNAL_PKI_UNSUPPORTED";
}

void WearOpenVpnClient::external_pki_sign_request(
    openvpn::ClientAPI::ExternalPKISignRequest& request
) {
    request.error = true;
    request.errorText = "EXTERNAL_PKI_UNSUPPORTED";
}

openvpn::Stop* WearOpenVpnClient::get_async_stop() {
    // OpenVPN's stop mechanism remembers an early stop request.
    return &stopToken_;
}

void WearOpenVpnClient::emit(
    TransportEventType type,
    TransportError error
) {
    if (!eventHandler_) {
        return;
    }

    TransportEvent outgoing;
    outgoing.type = type;
    outgoing.error = error;

    // Copy the metadata while still on the OpenVPN thread.
    // Other threads do not directly read address_.
    outgoing.address = address_;

    // The transport callback attaches a generation and queues the event.
    eventHandler_(outgoing);
}

void WearOpenVpnClient::closeTunFds() noexcept {
    if (tunOpenVpnFd_ >= 0) {
        close(tunOpenVpnFd_);
        tunOpenVpnFd_ = -1;
    }

    if (tunAppFd_ >= 0) {
        close(tunAppFd_);
        tunAppFd_ = -1;
    }
}

} //namespace wearvpn
