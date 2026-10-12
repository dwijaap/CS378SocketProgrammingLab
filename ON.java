import java.net.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

import org.w3c.dom.Node;

public class ON {

    static final int TCP_PORT = 5000;
    static final int MAX_NODES = 26;

    static class NodeInfo {
        char id;
        String ip;
        int udpPort;

        NodeInfo(char id, String ip, int udpPort) {
            this.id = id;
            this.ip = ip;
            this.udpPort = udpPort;
        }
    }

    static class NodeConnection {
        Socket socket;
        DataInputStream in;
        DataOutputStream out;
        NodeInfo node;

        NodeConnection(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }
    }

    static int[][] graph = new int[MAX_NODES][MAX_NODES];
    static int nodeCount;
    static NodeInfo[] nodes = new NodeInfo[MAX_NODES];
    static NodeConnection[] connections = new NodeConnection[MAX_NODES];
    static long configChanged = 0;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("Give config file name as input!");
            return;
        }

        String configFile = args[0];
        readConfig(configFile);
        configChanged = Files.getLastModifiedTime(Paths.get(configFile)).toMillis();
        System.out.println("Config File read");
        System.out.println("Number of nodes: " + nodeCount);

        InetAddress localAddress = InetAddress.getLocalHost();

        ServerSocket serverSocket = new ServerSocket(TCP_PORT, 30, localAddress);
        System.out.println("Server Info:\nTCP Port: " + TCP_PORT + "IP: " + localAddress.getHostAddress());

        char id = 'A';
        for (int i = 0; i < nodeCount; i++) {
            Socket socket = serverSocket.accept();
            System.out.println("TCP connection accepted for " + id + " node." +
                    "\n\tIP: " + socket.getInetAddress().getHostAddress());

            NodeConnection vn = new NodeConnection(socket);
            ConnectionInfo conInf = readConnection(vn.in);

            System.out.println("Connection for " + id + " - IP: " + conInf.ip + " UDP: " + conInf.udpPort);

            NodeInfo node = new NodeInfo(id, conInf.ip, conInf.udpPort);
            vn.node = node;
            nodes[i] = node;
            connections[i] = vn;
            id++;
        }

        sendAllLinkStates();

        while (true) {
            Thread.sleep(1000);

            long lastmodified = Files.getLastModifiedTime(Paths.get(configFile)).toMillis();
            if (lastmodified != configChanged) {
                System.out.println("Config file changed!!!!!!!!!!!!!!!!!!!!!!!");

                configChanged = lastmodified;

                readConfig(configFile);
                sendAllLinkStates();
            }
        }
    }

    static void readConfig(String file) throws IOException{
        for(int i = 0; i < MAX_NODES ; i++){
            for(int j = 0; j <MAX_NODES ; j++){
                graph[i][j] = -1;
                if(i==j)
                    graph[i][j] = 0;
            }
        }

        List<String> lines = Files.readAllLines(Paths.get(file));
        List<String> useful = new ArrayList<String>();
        for(String l : lines){
            l = l.trim();
            if(l.isEmpty() || l.startsWith("#"))
                continue;
            useful.add(l);
        }
        nodeCount = useful.size() + 1;
        if(nodeCount > MAX_NODES){
            throw new IOException("More than 26 nodes not allowed");
        }

        for(int i = 0 ; i <useful.size() ; i++){
            String[] costs = useful.get(i).split("\\s+");
            for(int j = 0; j< costs.length ; j++){
                int cost = Integer.parseInt(costs[j]);

                if((i+j+1)>= nodeCount){
                    throw new IOException("Invalid config file, length of graph not matching.");
                }
                graph[i][i+j+1] = cost;
                graph[i+j+1][i] = cost;
            }
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

    static ConnectionInfo readConnection(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length != 9) {
            throw new IOException("Not Connection message , wrong length");
        }
        byte[] body = new byte[length];
        in.readFully(body);
        ByteBuffer bytebuff = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);

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
        for (int i = 0; i < nodeCount; i++) {
            try {
                sendLinkState(i);
            } catch (IOException e) {
                System.out.println("Failed to send Link State to " + (char) ('A' + i));
            }
        }
    }

    static void sendLinkState(int i) throws IOException{
        NodeConnection vn = connections[i];
        ByteArrayOutputStream bodyStr = new ByteArrayOutputStream();
        DataOutputStream body = new DataOutputStream(bodyStr);

        body.writeByte(2);
        int tuples = 1;
        for(int j = 0 ; j < nodeCount ; j++){
            if(i!=j && graph[i][j] != 0){
                tuples++;
            }
        }
        body.writeByte(tuples);
        writeTuple(body, nodes[i], )
    }
}
