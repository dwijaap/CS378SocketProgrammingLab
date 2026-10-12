
#include <arpa/inet.h>
#include <sys/socket.h>
#include <sys/select.h>
#include <unistd.h>

#include <array>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <map>
#include <string>
#include <vector>

using namespace std;
using Clock = chrono::steady_clock;

const int NODE_COUNT = 26;
const int ON_TCP_PORT = 5000;

struct Neighbor {
    sockaddr_in addr{};
    int cost;
};

static int tcpSock = -1;
static int udpSock = -1; // For LSP later

static sockaddr_in myAddr{};
static int myPort = 0;
static int myId = -1;
static bool haveConfig = false;

static map<int, Neighbor> neighbors;
static array<map<int, int>, NODE_COUNT> graph; // again for LSP later
static array<int64_t, NODE_COUNT> lastSeqNo; // again for LSP later

static vector<uint8_t> tcpBuffer; 

static uint32_t readU32(const uint8_t* p) {
    return (uint32_t(p[0]) << 24) |
           (uint32_t(p[1]) << 16) |
           (uint32_t(p[2]) << 8) |
           uint32_t(p[3]);
}

static void appendU32(vector<uint8_t>& v, uint32_t x) {
    v.push_back((x >> 24) & 255);
    v.push_back((x >> 16) & 255);
    v.push_back((x >> 8) & 255);
    v.push_back(x & 255);
}

static bool sendAll(int fd, const uint8_t* data, size_t len) {
    size_t sent = 0;

    while (sent < len) {
        ssize_t n = send(fd, data + sent, len - sent, 0);

        if (n <= 0) return false;
        sent += static_cast<size_t>(n);
    }

    return true;
}

static bool sendTcpFrame(const vector<uint8_t>& body) {
    uint32_t len = static_cast<uint32_t>(body.size());
    vector<uint8_t> frame;

    appendU32(frame, len);
    frame.insert(frame.end(), body.begin(), body.end());

    return sendAll(tcpSock, frame.data(), frame.size());
}

static bool sendConnect() {
    vector<uint8_t> body;
    body.push_back(1); // CONNECT message type = 1
    
    const uint8_t* ip = 
        reinterpret_cast<const uint8_t*>(&myAddr.sin_addr.s_addr);
    body.insert(body.end(), ip, ip + 4);

    appendU32(body, static_cast<uint32_t>(myPort));

    return sendTcpFrame(body);
}

static void printNeighbourhood() {
    cout << "Node " << char('A' + myId) << " neighbourhood:" << endl;

    cout << char('A' + myId) << "=0";

    for(const auto& [id, neighbor] : neighbors) {
        cout << ", " << char('A' + id) << "=" << neighbor.cost;
    }

    cout << endl;
}

static void printGraph() { // For LSP later
    cout << "Current graph:" << endl;

    for(int i = 0; i < NODE_COUNT; ++i) {
        if(lastSeqNo[i] < 0) continue;

        cout << char('A' + i) << ": ";

        bool first = true;

        for(const auto& [id, cost] : graph[i]) {
            if(!first) cout << ", ";
            cout << char('A' + id) << "=" << cost;
            first = false;
        }

        if(first) cout << "(no known neighbors)";
        cout << endl;
    }
}

static void handleLinkState(const uint8_t* body, size_t len) {
    if(len < 2 || body[0] != 2) return; // LINK-STATE message type = 2

    int count = body[1];

    if(len != 2 + static_cast<size_t>(count) * 13) {
        cerr << "Invalid LINK-STATE message length" << endl;
        return;
    }

    map<int, Neighbor> newNeighbors;
    int newMyId = -1; // should not change but message has it 
    size_t pos = 2; // start reading after type and count

    for(int i = 0; i < count; i++){
        int id = body[pos++];

        if(id < 'A' || id > 'Z') {
            cerr << "Invalid node ID from ON" << endl;
            return; 
        }

        sockaddr_in addr{};
        addr.sin_family = AF_INET;

        memcpy(&addr.sin_addr.s_addr, body + pos, 4);
        pos += 4; // Reading 4 bytes for IP address

        uint32_t port = readU32(body + pos);
        pos += 4; // Reading 4 bytes for port

        uint32_t cost = readU32(body + pos);
        pos += 4; // Reading 4 bytes for cost

        if(port == 0 || port > 65535) {
            cerr << "Invalid UDP port from ON" << endl;
            return;
        }

        addr.sin_port = htons(static_cast<uint16_t>(port));

        int nodeId = id - 'A';

        if(cost == 0) { // Own own node entry
            if(newMyId != -1) {
                cerr << "Multiple self entries in LINK-STATE" << endl;
                return;
            }
            newMyId = nodeId;

        } else {
            newNeighbors[nodeId] = Neighbor{addr, static_cast<int>(cost)};
        }

    }

    if(newMyId == -1) {
        cerr << "LINK-STATE has no self entry" << endl;
        return;
    }

    myId = newMyId;
    neighbors = newNeighbors;
    haveConfig = true;

    printNeighbourhood();


    // For LSP later
}

static bool processTcpFrames() {
    while(tcpBuffer.size() >= 4) {
        uint32_t len = readU32(tcpBuffer.data());

        if (len == 0 || len > 65536) {
            cerr << "Invalid TCP frame length\n";
            return false;
        }

        if(tcpBuffer.size() < 4 + len) return true; // so if the full message hasn't arrived yet, we just wait for more data

        vector<uint8_t> body(
            tcpBuffer.begin() + 4,
            tcpBuffer.begin() + 4 + len
        );

        if (!body.empty() && body[0] == 2) {
            handleLinkState(body.data(), body.size());
        } else {
            cerr << "Unexpected message from ON\n";
        }

        tcpBuffer.erase(tcpBuffer.begin(), tcpBuffer.begin() + 4 + len);
    }
    return true;
}

static bool connectToOn(const char* onIp) {
    tcpSock = socket(AF_INET, SOCK_STREAM, 0);

    if (tcpSock < 0) {
        perror("TCP socket");
        return false;
    }

    sockaddr_in onAddr{};
    onAddr.sin_family = AF_INET;
    onAddr.sin_port = htons(ON_TCP_PORT);

    if (inet_pton(AF_INET, onIp, &onAddr.sin_addr) != 1) {
        cerr << "Invalid ON IPv4 address\n";
        return false;
    }

    if (connect(tcpSock,
                reinterpret_cast<sockaddr*>(&onAddr),
                sizeof(onAddr)) < 0) {
        perror("couldn't connect to ON");
        return false;
    }

    if (!sendConnect()) {
        cerr << "Failed to send CONNECT\n";
        return false;
    }

    return true;
}

int main(int argc, char* argv[]) {
    if (argc != 4) {
        cerr << "Usage: " << argv[0]
             << " <ON_IP> <OWN_IP> <UDP_PORT>\n";
        return 1;
    }

    myPort = stoi(argv[3]);

    if (myPort < 1 || myPort > 65535) {
        cerr << "Invalid UDP port\n";
        return 1;
    }

    myAddr.sin_family = AF_INET;
    myAddr.sin_port = htons(static_cast<uint16_t>(myPort));

    if (inet_pton(AF_INET, argv[2],
                  &myAddr.sin_addr) != 1) {
        cerr << "My IPv4 address is Invalid\n";
        return 1;
    }

    // Set up UDP socket.
    udpSock = socket(AF_INET, SOCK_DGRAM, 0);

    if (udpSock < 0) {
        perror("UDP socket");
        return 1;
    }

    if (bind(udpSock,
             reinterpret_cast<sockaddr*>(&myAddr),
             sizeof(myAddr)) < 0) {
        perror("UDP bind");
        close(udpSock);
        return 1;
    }

    // Connect to Oracle Node over TCP.
    if (!connectToOn(argv[1])) {
        if (tcpSock >= 0) close(tcpSock);
        close(udpSock);
        return 1;
    }

    cout << "Connected to ON; waiting for LINK-STATE...\n";

    while (true) {
        uint8_t buffer[4096];

        ssize_t n = recv(tcpSock, buffer, sizeof(buffer), 0);

        if(n <= 0) {
            if(n == 0) {
                cerr << "ON disconnected\n";
                break;
            } else {
                perror("TCP recv error");
                break;
            }
        }
        tcpBuffer.insert(tcpBuffer.end(), buffer, buffer + n);

        if(!processTcpFrames()) break;
    }

    close(tcpSock);
    close(udpSock);
    return 0;
}