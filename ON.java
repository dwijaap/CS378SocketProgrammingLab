import java.net.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.*;
import java.util.*;

public class ON {

    static final int TCP_PORT = 5000;
    static final int MAX_NODES = 26;

    static class NodeConnection {
        SocketChannel channel;
        ByteBuffer inBuffer = ByteBuffer.allocate(4096);
        SelectionKey key;
        char id;
        String ip;
        int udpPort;
        boolean conn = false;

        NodeConnection(SocketChannel chan) throws IOException {
            this.channel = chan;
        }
    }

    static int[][] graph = new int[MAX_NODES][MAX_NODES];
    static int nodeCount;
    static int allNodeCount;
    static NodeConnection[] connections = new NodeConnection[MAX_NODES];
    static long configChanged = 0;
    static int nextId = 0;
    static boolean ready = false;

    public static void main(String[] args) throws Exception {

        if (args.length < 1 || args.length > 2) {
            System.out.println("Usage: java ON <config_file> [ON_IP]");
            return;
        }

        String bindIP = null;
        String configFile = args[0];

        if (args.length == 2) {
            bindIP = args[1];
        }

        readConfig(configFile);
        configChanged = Files.getLastModifiedTime(Paths.get(configFile)).toMillis();
        System.out.println("Config File read");
        System.out.println("Number of nodes in config file: " + nodeCount);

        InetAddress localAddress = InetAddress.getLocalHost();

        Selector sel = Selector.open();
        ServerSocketChannel server = ServerSocketChannel.open();
        server.setOption(StandardSocketOptions.SO_REUSEADDR, true);

        if (bindIP != null) {
            try {
                server.bind(new InetSocketAddress(bindIP, TCP_PORT), 30);
                localAddress = InetAddress.getByName(bindIP);
            } catch (Exception e) {
                System.out.println("Provided IP for ON is not valid, so binding to the local Host IP!!!!");
                server.bind(new InetSocketAddress(localAddress, TCP_PORT), 30);
            }
        }
        else{
            server.bind(new InetSocketAddress(localAddress, TCP_PORT), 30);
        }
        server.configureBlocking(false);
        server.register(sel, SelectionKey.OP_ACCEPT);

        System.out.println("ON TCP Port: " + TCP_PORT + "\nON IP: " + localAddress.getHostAddress());

        try {
            while (true) {
                sel.select(1000);

                Iterator<SelectionKey> it = sel.selectedKeys().iterator();

                while (it.hasNext()) {
                    SelectionKey k = it.next();
                    it.remove();
                    if (!k.isValid()) {
                        continue;
                    }
                    if (k.isAcceptable()) {
                        acceptConnection(server, sel);
                    }
                    if (k.isReadable()) {
                        handleRead(k);
                    }
                }

                long lastmodified = Files.getLastModifiedTime(Paths.get(configFile)).toMillis();
                if (lastmodified != configChanged) {
                    System.out.println("Config file changed!!!!!!!!!!!!!!!!!!!!!!!");

                    configChanged = lastmodified;

                    readConfig(configFile);
                    if (ready)
                        sendAllLinkStates();
                }
            }
        } finally {
            if (server != null) {
                server.close();
            }
            sel.close();
            for (int i = 0; i < allNodeCount; i++) {
                if (connections[i].channel != null)
                    connections[i].channel.close();
            }
            System.out.println("Server Socket Closed!");
        }
    }

    static void acceptConnection(ServerSocketChannel server, Selector sel) throws IOException {
        SocketChannel ch = server.accept();
        if (ch == null)
            return;
        if (nextId >= MAX_NODES) {
            System.out.println("26 nodes already present!");
            ch.close();
            return;
        }
        ch.configureBlocking(false);
        connections[nextId] = new NodeConnection(ch);
        // nodes[nextId] = new NodeInfo( (char)('A'+nextId), null, 0);
        connections[nextId].id = (char) (nextId + 'A');
        connections[nextId].key = ch.register(sel, SelectionKey.OP_READ, connections[nextId]);

        allNodeCount++;
        if (nextId < nodeCount) {
            System.out.println("TCP Connection accepted for " + (char) (nextId + 'A') + ".");
        } else {
            System.out.println("TCP connection accepted for isolated island " + (char) (nextId + 'A') + ".");
        }

        nextId++;
    }

    static void handleRead(SelectionKey key) {
        NodeConnection nc = (NodeConnection) key.attachment();

        try {
            int n = nc.channel.read(nc.inBuffer);
            if (n < 0) {
                closeConnection(nc);
                return;
            }

            nc.inBuffer.flip();
            while (nc.inBuffer.remaining() >= 4) {
                int len = nc.inBuffer.getInt(nc.inBuffer.position());

                if (len <= 0 || len > 1024) {
                    throw new IOException("Bad frame length");
                }

                // wait for the remaining message
                if (nc.inBuffer.remaining() < 4 + len) {
                    break;
                }

                nc.inBuffer.getInt();
                byte[] body = new byte[len];
                nc.inBuffer.get(body);
                ConnectionInfo inf = readConnection(body);
                nc.ip = inf.ip;
                nc.udpPort = inf.udpPort;
                nc.conn = true;
                System.out.println("Connected VN " + nc.id + " on udp port " + nc.udpPort + " ip : " + nc.ip);

                if (!ready && allConnected()) {
                    ready = true;
                    sendAllLinkStates();
                } else if (ready) {
                    sendLinkState((nc.id - 'A'));
                }
            }
            nc.inBuffer.compact();
        } catch (IOException e) {
            System.out.println("Error on node : " + nc.id + " : " + e.getMessage());
            closeConnection(nc);
        }
    }

    static void closeConnection(NodeConnection nc) {
        System.out.println("Node Disconnected " + nc.id);
        nc.key.cancel();
        try {
            nc.channel.close();
        } catch (IOException e) {
        }
        connections[nc.id - 'A'] = null;
    }

    static boolean allConnected() {
        for (int i = 0; i < nodeCount; i++) {
            if (connections[i] == null || connections[i].conn == false)
                return false;
        }
        return true;
    }

    static void readConfig(String file) throws IOException {
        for (int i = 0; i < MAX_NODES; i++) {
            for (int j = 0; j < MAX_NODES; j++) {
                graph[i][j] = -1;
                if (i == j)
                    graph[i][j] = 0;
            }
        }

        List<String> lines = Files.readAllLines(Paths.get(file));
        List<String> useful = new ArrayList<String>();
        for (String l : lines) {
            l = l.trim();
            if (l.isEmpty() || l.startsWith("#"))
                continue;
            useful.add(l);
        }
        nodeCount = useful.size() + 1;
        if (nodeCount > MAX_NODES) {
            throw new IOException("More than 26 nodes not allowed");
        }

        for (int i = 0; i < useful.size(); i++) {
            String[] costs = useful.get(i).split("\\s+");
            for (int j = 0; j < costs.length; j++) {
                int cost = Integer.parseInt(costs[j]);

                if ((i + j + 1) >= nodeCount) {
                    throw new IOException("Invalid config file, length of graph not matching.");
                }
                graph[i][i + j + 1] = cost;
                graph[i + j + 1][i] = cost;
            }
        }

        printGraph();
    }

    static void printGraph() {
        System.out.println("Current Graph:");

        int i = 0;
        for (; i < nodeCount; i++) {
            for (int j = i + 1; j < nodeCount; j++) {
                System.out.print((char) ('A' + i) + "->" + (char) ('A' + j) + " = " + graph[i][j] + "\t");
            }
            System.out.println();
        }

        if (nodeCount == 1) {
            System.out.println("A -> isolated node");
        }

        for (; i < allNodeCount; i++) {
            if (connections[i] != null && connections[i].conn == true)
                System.out.println((char) ('A' + i) + " -> isolated node");
        }
    }

    static class ConnectionInfo {
        String ip;
        int udpPort;

        ConnectionInfo(String ip, int udpPort) {
            this.ip = ip;
            this.udpPort = udpPort;
        }
    }

    static ConnectionInfo readConnection(byte[] in) throws IOException {
        int length = in.length;
        if (length != 9) {
            throw new IOException("Not Connection message , wrong length");
        }

        ByteBuffer bytebuff = ByteBuffer.wrap(in).order(ByteOrder.BIG_ENDIAN);

        byte type = bytebuff.get();
        if (type != 1) {
            throw new IOException("Not Connection message, wrong type");
        }
        int ip = bytebuff.getInt();
        int udp = bytebuff.getInt();

        String ipaddr = "" + ((ip >> 24) & 255) + "." + ((ip >> 16) & 255) + "." + ((ip >> 8) & 255) + "."
                + ((ip) & 255);

        return new ConnectionInfo(ipaddr, udp);
    }

    static void sendAllLinkStates() {
        if (!allConnected()) {
            ready = false;
            return;
        }
        for (int i = 0; i < allNodeCount; i++) {
            try {
                if (i < nodeCount)
                    sendLinkState(i);
                else if (connections[i].conn)
                    sendLinkState(i);
            } catch (IOException e) {
                System.out.println("Failed to send Link State to " + (char) ('A' + i));
            }
        }
    }

    static void sendLinkState(int i) throws IOException {
        NodeConnection nc = connections[i];
        ByteArrayOutputStream bodyStr = new ByteArrayOutputStream();
        DataOutputStream body = new DataOutputStream(bodyStr);

        body.writeByte(2);
        int tuples = 1;
        for (int j = 0; j < nodeCount; j++) {
            if (i != j && graph[i][j] > 0) {
                tuples++;
            }
        }
        body.writeByte(tuples);

        body.writeByte((char) ('A' + i));
        body.writeInt(ipv4toInt(nc.ip));
        body.writeInt(nc.udpPort);
        body.writeInt(0);

        for (int j = 0; j < nodeCount; j++) {
            if (j == i || graph[i][j] <= 0) {
                continue;
            }
            body.writeByte((char) ('A' + j));
            body.writeInt(ipv4toInt(connections[j].ip));
            body.writeInt(connections[j].udpPort);
            body.writeInt(graph[i][j]);
        }

        body.flush();
        byte[] msg = bodyStr.toByteArray();

        ByteBuffer buf = ByteBuffer.allocate(4 + msg.length);

        buf.putInt(msg.length);
        buf.put(msg);
        buf.flip();

        while (buf.hasRemaining()) {
            nc.channel.write(buf);
        }

        System.out.println("Sent Link State to node " + (char) ('A' + i));
    }

    static int ipv4toInt(String ip) throws IOException {
        String parts[] = ip.split("\\.");

        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            int c = Integer.parseInt(parts[2]);
            int d = Integer.parseInt(parts[3]);

            if (a < 0 || a > 255 || b < 0 || b > 255 || c < 0 || c > 255 || d < 0 || d > 255)
                throw new IOException("Wrong ipv4");

            return (a << 24) | (b << 16) | (c << 8) | d;
        } catch (NumberFormatException e) {
            throw new IOException("Wrong ipv4 " + ip, e);
        }
    }

}
