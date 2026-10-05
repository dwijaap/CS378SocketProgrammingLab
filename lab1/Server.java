import java.io.*;
import java.net.*;
import java.util.*;

public class Server{
    public static void main(String[] args){
        int port = 8079;

        Map<String, String> store = new HashMap<>();

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            
            System.out.println("Server has just gotten started on port "
                + port + ". I am waiting for a client.");

            Socket clientSocket = serverSocket.accept();
            System.out.println("WE FOUND A CLIENT: " + clientSocket.getInetAddress());

            InputStream in = clientSocket.getInputStream();
            OutputStream out = clientSocket.getOutputStream();

            byte[] buffer = new byte[1024];
            int bytesRead;

            while((bytesRead = in.read(buffer)) != -1){
                String request = new String(buffer, 0, bytesRead).trim();
                String[] parts = request.split(" ");
                String command = parts[0].toUpperCase();
                String response = "ERROR: INVALID COMMAND";
                
                if(command.equals("GET") && parts.length >= 2){
                    String value = store.get(parts[1]);
                    response = (value != null ? value : "NOT_FOUND") + "\n";
                } else if(command.equals("SET") && parts.length >= 3 ) {
                    store.put(parts[1], parts[2]);
                    response = "OK BOSS\n";
                }

                out.write(response.getBytes());
                out.flush();
            }

            clientSocket.close();
        } 
        catch (IOException e) {
            System.err.println("Error aa gaya baba: " + e.getMessage());
        }
    }
}