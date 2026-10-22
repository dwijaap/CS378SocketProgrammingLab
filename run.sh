
#!/usr/bin/env bash

set -uo pipefail

mkdir -p ./logs

ON_PID=""
declare -a VN_PIDS=()
declare -a ADDED_IPS=()
CLEANING_UP=0

cleanup() {
    if (( CLEANING_UP )); then
        return
    fi
    CLEANING_UP=1

    trap - INT TERM EXIT

    echo
    echo "Stopping all Virtual Nodes and the Oracle Node..."

    # Stop all VNs started by this script.
    for pid in "${VN_PIDS[@]}"; do
        kill -TERM "$pid" 2>/dev/null || true
    done

    # Stop the ON.
    if [[ -n "$ON_PID" ]]; then
        kill -TERM "$ON_PID" 2>/dev/null || true
    fi

    # Wait for processes to terminate.
    for pid in "${VN_PIDS[@]}"; do
        wait "$pid" 2>/dev/null || true
    done

    if [[ -n "$ON_PID" ]]; then
        wait "$ON_PID" 2>/dev/null || true
    fi

    # Remove only the IP aliases added by this script.
    for ip in "${ADDED_IPS[@]}"; do
        echo "Removing temporary IP $ip from $IFACE..."
        sudo ip addr del "$ip/32" dev "$IFACE" 2>/dev/null || true
    done

    echo "All processes stopped."
    echo "ON log: ./logs/on.log"
    echo "VN logs: ./logs/vnA.log, ./logs/vnB.log, ..."
    exit 0
}

trap cleanup INT TERM EXIT

# --------------------------------------------------
# 1. Check dependencies and compile
# --------------------------------------------------

for cmd in ip javac java g++; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "Required command not found: $cmd"
        exit 1
    fi
done

echo "Compiling ON and VN..."

if ! javac ON.java; then
    echo "Java compilation failed."
    exit 1
fi

if ! g++ -std=c++17 -Wall -Wextra VN.cpp -o vn; then
    echo "C++ compilation failed."
    exit 1
fi

# --------------------------------------------------
# 2. Get VN configurations
# --------------------------------------------------

read -rp "Number of VNs to launch (1-26): " N

if ! [[ "$N" =~ ^[1-9][0-9]*$ ]] || (( N > 26 )); then
    echo "Enter a number between 1 and 26."
    exit 1
fi

declare -a VN_IPS=()
declare -a VN_PORTS=()

for ((i = 0; i < N; i++)); do
    printf -v NODE_LETTER "\\$(printf '%03o' "$((65 + i))")"

    echo
    echo "Configuration for VN $NODE_LETTER"

    read -rp "  VN IP address: " ip
    read -rp "  UDP port: " port

    if ! [[ "$ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; then
        echo "Invalid IPv4 address: $ip"
        exit 1
    fi

    if ! [[ "$port" =~ ^[0-9]+$ ]] ||
       (( 10#$port < 1 || 10#$port > 65535 )); then
        echo "Invalid UDP port: $port"
        exit 1
    fi

    VN_IPS+=("$ip")
    VN_PORTS+=("$port")
done

# --------------------------------------------------
# 3. Determine the network interface
# --------------------------------------------------

IFACE=$(ip route show default | awk 'NR == 1 {print $5}')

if [[ -z "$IFACE" ]]; then
    echo "Could not detect the default network interface."
    echo "Check 'ip route' and configure IFACE manually."
    exit 1
fi

echo
echo "Using network interface: $IFACE"

# --------------------------------------------------
# 4. Ensure every VN IP is assigned locally
# --------------------------------------------------

for ((i = 0; i < N; i++)); do
    ip="${VN_IPS[i]}"

    if ip -o -4 addr show |
       awk -v wanted="$ip" '
           {
               split($4, a, "/")
               if (a[1] == wanted) found = 1
           }
           END { exit !found }
       '; then

        echo "IP $ip is already assigned."

    else
        echo "IP $ip is not assigned. Adding $ip/32 to $IFACE..."

        # sudo may prompt for your password.
        if ! sudo ip addr add "$ip/32" dev "$IFACE"; then
            echo "Failed to add IP $ip."
            exit 1
        fi

        ADDED_IPS+=("$ip")
        echo "Added IP $ip successfully."
    fi
done

# --------------------------------------------------
# 5. Start Oracle Node
# --------------------------------------------------

: > ./logs/on.log

echo
echo "Starting Oracle Node..."

java ON config.txt > ./logs/on.log 2>&1 &
ON_PID=$!

# Wait for ON to print its IPv4 address.
# Expected format: ON IP: 192.168.1.10

ON_IP=""

for ((i = 0; i < 30; i++)); do
    if ! kill -0 "$ON_PID" 2>/dev/null; then
        echo "Oracle Node exited unexpectedly:"
        cat ./logs/on.log
        exit 1
    fi

    ON_IP=$(sed -nE \
        's/.*ON IP:[[:space:]]*([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+).*/\1/p' \
        ./logs/on.log | head -n 1)

    if [[ -n "$ON_IP" ]]; then
        break
    fi

    sleep 1
done

if [[ -z "$ON_IP" ]]; then
    echo "Could not detect ON IP from on.log."
    echo "Expected: ON IP: <IPv4 address>"
    echo "Check whether on.java prints its IP."
    cat ./logs/on.log
    exit 1
fi

echo "Oracle Node IP detected: $ON_IP"

# --------------------------------------------------
# 6. Launch each VN with a separate log file
# --------------------------------------------------

for ((i = 0; i < N; i++)); do
    printf -v NODE_LETTER "\\$(printf '%03o' "$((65 + i))")"

    LOG_FILE="./logs/vn${NODE_LETTER}.log"

    # Truncate old logs.
    : > "$LOG_FILE"

    echo "Starting VN $NODE_LETTER..."
    echo "  IP: $ON_IP -> ${VN_IPS[i]}:${VN_PORTS[i]}"
    echo "  Log: $LOG_FILE"

    ./vn "$ON_IP" "${VN_IPS[i]}" "${VN_PORTS[i]}" \
        > "$LOG_FILE" 2>&1 &

    VN_PIDS+=("$!")
done

# --------------------------------------------------
# 7. Wait for user command
# --------------------------------------------------

echo
echo "All VNs launched."
echo "ON output: ./logs/on.log"
echo "Individual VN outputs: ./logs/vnA.log, ./logs/vnB.log, ..."
echo
echo "Type 'exit' and press Enter to stop everything."
echo "Press Ctrl+C to stop everything."
echo

while true; do
    if ! IFS= read -r command; then
        echo "Input closed."
        break
    fi

    if [[ "$command" == "exit" ]]; then
        break
    fi

    echo "Unknown command. Type 'exit' to stop."
done

cleanup