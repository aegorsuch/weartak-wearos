#pragma once

#include <functional>
#include <string>

#include <client/ovpncli.hpp>
#include <openvpn/common/stop.hpp>

#include "OpenVpnTransport.h"

// Declare the OpenVPNClient class, which is a wrapper around the OpenVPN3 client library.
//supplies a software TUN using local datagram socketpair
//Traslates Ovpn events to a callback interface for application event handling
//NativeTunnel handles the lwIP stack and the TUN interface, and provides a callback for the application to handle events
namespace wearvpn {
    class WearOpenVpnClient final : public openvpn::ClientAPI::OpenVPNClient {
    public:
        using EventHandler = std::function<void(TransportEvent)>;

        // Stop token belongs to OpenVpnTransport and must outlive this client.
        WearOpenVpnClient(
            openvpn::Stop& stopToken,
            EventHandler eventHandler
        );

        ~WearOpenVpnClient() override;

        // Creates the local packet connection between the application
        // and OpenVPN. Call before starting OpenVPN's connection thread.
        bool initializeTunSocketPair() noexcept;

        // Returns a borrowed descriptor for application packet I/O.
        // OpenVpnTransport may read/write it, but must not close it.
        int appTunFd() const noexcept;

        // OpenVPN tunnel-builder callbacks
        bool tun_builder_new() override;
        bool tun_builder_set_layer(int layer) override;
        bool tun_builder_set_remote_address(
            const std::string& address,
            bool ipv6
        ) override;
        bool tun_builder_add_address(
            const std::string& address,
            int prefixLength,
            const std::string& gateway,
            bool ipv6,
            bool net30
        ) override;
        bool tun_builder_add_route(
            const std::string& address,
            int prefixLength,
            int metric,
            bool ipv6
        ) override;
        bool tun_builder_set_dns_options(
            const openvpn::DnsOptions& dns
        ) override;
        bool tun_builder_set_mtu(int mtu) override;
        bool tun_builder_set_session_name(
            const std::string& name
        ) override;

        // Gives OpenVPN its own descriptor for the software TUN.
        int tun_builder_establish() override;
        void tun_builder_teardown(bool disconnect) override;

        // OpenVPN connection callbacks
        bool socket_protect(
            openvpn_io::detail::socket_type socket,
            std::string remote,
            bool ipv6
        ) override;
        bool pause_on_connection_timeout() override;
        void event(
            const openvpn::ClientAPI::Event& event
        ) override;
        void acc_event(
            const openvpn::ClientAPI::AppCustomControlMessageEvent& event
        ) override;
        void log(
            const openvpn::ClientAPI::LogInfo& info
        ) override;
        void external_pki_cert_request(
            openvpn::ClientAPI::ExternalPKICertRequest& request
        ) override;
        void external_pki_sign_request(
            openvpn::ClientAPI::ExternalPKISignRequest& request
        ) override;

    protected:
        // OpenVPN attaches to this token during connection setup.
        // It remembers a stop requested before setup finishes.
        openvpn::Stop* get_async_stop() override;

    private:
        // Copies safe metadata into an application event.
        void emit(
            TransportEventType type,
            TransportError error
        );

        // Closes only descriptors owned by this adapter.
        void closeTunFds() noexcept;

        // A reference to the transport-owned stop token.
        openvpn::Stop& stopToken_;

        EventHandler eventHandler_;

        // These fields are modified by OpenVPN's connection thread.
        // Other threads receive copies through queued events.
        TunnelAddress address_{};
        bool addressAssigned_ = false;
        bool established_ = false;
        int layer_ = 3;

        // This adapter owns the original socketpair endpoints.
        // OpenVPN receives a duplicate of tunOpenVpnFd_ and owns that duplicate separately.
        int tunOpenVpnFd_ = -1;
        int tunAppFd_ = -1;
    };

} // namespace wearvpn