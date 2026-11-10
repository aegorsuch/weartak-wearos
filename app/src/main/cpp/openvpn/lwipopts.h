#ifndef WEARVPN_LWIPOPTS_H
#define WEARVPN_LWIPOPTS_H

// The application supplies one owner thread for all lwIP operations.
#define NO_SYS                         1
#define SYS_LIGHTWEIGHT_PROT           0

// Use the raw callback API.
#define LWIP_SOCKET                    0
#define LWIP_NETCONN                   0

// IPv4 software tunnel.
#define LWIP_IPV4                      1
#define LWIP_IPV6                      0
#define LWIP_TCP                       1
#define LWIP_UDP                       0
#define LWIP_ICMP                      1
#define LWIP_RAW                       1

// No Ethernet interface, ARP, DHCP or native DNS resolver.
#define LWIP_ETHERNET                  0
#define LWIP_ARP                       0
#define LWIP_DHCP                      0
#define LWIP_DNS                       0
#define LWIP_IGMP                      0
#define LWIP_AUTOIP                    0

// Explicitly bounded lwIP allocation pools.
#define MEM_ALIGNMENT                  4
#define MEM_SIZE                       (1024 * 1024)

#define MEMP_NUM_TCP_PCB                16
#define MEMP_NUM_TCP_PCB_LISTEN         0
#define MEMP_NUM_TCP_SEG                256
#define PBUF_POOL_SIZE                 128

#define TCP_MSS                        1360
#define TCP_SND_BUF                    (32 * TCP_MSS)
#define TCP_SND_QUEUELEN                \
    ((4 * TCP_SND_BUF + TCP_MSS - 1) / TCP_MSS)
#define TCP_WND                        (32 * TCP_MSS)

// Keep third-party debug output disabled.
#define LWIP_DEBUG                     0

#endif