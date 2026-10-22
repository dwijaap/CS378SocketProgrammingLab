# CS-378 Socket Programming

### Videep - 24B1037 , Dwija - 24B0995

## Files

- `VN.cpp` — Implements the Virtual Node (VN), which connects to the Oracle Node (ON) using TCP and receives its neighbourhood information. It also connects to other neighbouring virtual nodes (if any) using UDP for Link State Protocol.
- `ON.java` — Implements the Oracle Node (ON), which reads the network topology from the configuration file and sends link-state information to connected VNs.
- `config.txt` — Specifies the network topology using an upper-triangular adjacency matrix.

## Requirements

- Java JDK
- C++ compiler supporting the socket APIs used in `VN.cpp`
- Valid IP addresses for the ON and VNs
- Network connectivity between the processes

**Note:** Do not use loopback addresses such as `127.0.0.1` or `0.0.0.0` as the argument to `VN.cpp`

## Configuration File

The `config.txt` file specifies the network topology.

- Each row represents a node, starting with node A.
- The first row contains link costs from A to B, C, D, and subsequent nodes.
- The second row contains link costs from B to C, D, and subsequent nodes.
- Continue similarly for the remaining nodes.
- A cost of `-1` indicates that no link exists.
- Link costs are symmetric.
- Blank lines and lines beginning with `#` are ignored.

Example:

```text
3  1  -1  -1
   2   4  -1
      -1   1
           7
```

This represents a network of five nodes, A through E.

## Compilation and Execution

### 1. Start the Oracle Node

Compile the Java program:

```bash
javac ON.java
```

Run the Oracle Node with the configuration file:

```bash
java ON config.txt
```

The ON listens for incoming TCP connections on port `5000` (as mentioned in the assignment). Start it before launching the VNs (you can start it after launching VNs as well, provided you know the IP address of ON).

### 2. Compile the Virtual Node

Compile the C++ program using:

```bash
g++ -std=c++17 VN.cpp -o vn
```

### 3. Start the Virtual Nodes

Run each VN in a separate terminal:

```bash
./vn <ON_IP> <OWN_IP> <UDP_PORT>
```

Arguments:

- `ON_IP`: IP address of the machine running the Oracle Node (printed on running the ON).
- `OWN_IP`: IP address used by the Virtual Node (make sure it is a valid ip address displayed by ip addr command (in Linux))
- `UDP_PORT`: UDP port on which this Virtual Node listens.

Example:

```bash
./vn 192.168.1.10 192.168.1.11 5001
```

Each VN should use a distinct IP-address/UDP-port pair.

Start the number of VNs specified by `config.txt` (or greater than that due to islands).

## Expected Behaviour

1. Each VN binds its UDP socket and establishes a TCP connection to the ON on port `5000`.
2. Each VN sends a `CONNECT` message containing its IP address and UDP port in binary format.
3. Once the ON has received connections from all configured nodes, it sends each VN a `LINK-STATE` message.
4. Each VN displays its assigned node identity and neighbouring nodes with their link costs.
5. Link State Protocol is used to send Link State Messages to neighbourse which include triggered updates (on receiving new Link State information from Oracle Node) and periodic updates (sent every 15 seconds)
6. The Virtual Node also regularly prints the current graph known by it (every 10 seconds)

The output depends on the configured topology.

### Running the Program using run.sh

Run the script using `bash run.sh` from the assignment directory. The script compiles `ON.java` and `VN.cpp`, starts the Oracle Node, and prompts you to enter the number of Virtual Nodes along with each VN's IP address and UDP port. It automatically configures any missing local IP aliases required by the VNs and launches each Virtual Node. The output of the Oracle Node and each Virtual Node is saved separately in the `logs/` directory (`on.log`, `vnA.log`, `vnB.log`, etc.). Enter `exit` in the terminal or press `Ctrl+C` to stop the program; the script terminates the launched processes and removes any IP aliases it added.

## Troubleshooting

- **Connection refused:** Ensure that the ON is running and that `ON_IP` is correct.
- **UDP bind error:** Ensure that `OWN_IP` belongs to an available network interface and that the UDP port is not already in use.
- **No link-state output:** Verify that all expected VNs are running and have connected successfully.
- **Port already in use:** Stop the process occupying the port or select another permitted UDP port.

## Notes

- The ON reads the configuration file specified in its command-line arguments.
- The ON and VN are implemented in different programming languages.
- To end the interaction, kill the java process corresponding to the Oracle Node which will lead to disconnection of all the Virtual nodes connected to it, causing all of them to stop.
