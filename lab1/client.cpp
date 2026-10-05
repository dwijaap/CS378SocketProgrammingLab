#include <iostream>
#include <sys/socket.h>
#include <unistd.h>
#include <bits/stdc++.h>
#include <netinet/in.h>
#include <cstring>

int main() {

    int clientSocket = socket(AF_INET, SOCK_STREAM, 0);
    if(clientSocket == -1){
        std::cerr << "Failed to create clientSocket :( " << std::endl;
        return 1;
    }

    struct sockaddr_in serverAddr;
    serverAddr.sin_family = AF_INET;
    serverAddr.sin_port = htons(8079);

    

    if (connect(clientSocket, (struct sockaddr*)&serverAddr, sizeof(serverAddr)) == -1){
        std::cerr << "Connection nahi hua boss server chal raha hein kya ? " << std::endl;
        close(clientSocket);
        return 1;
    }

    std::cout << "Connected to JAVA SERVER " << std::endl;
    std::cout << "Type commands GET SET nogo" << std::endl;

    std::string command;
    char buffer[1024];

    while(true) {
        std::cout << "input de bhai > ";
        std::getline(std::cin, command);

        if(command == "nogo") break;

        send(clientSocket, command.c_str(), command.length(), 0);

        memset(buffer, 0, sizeof(buffer));

        int bytesReceived = recv(clientSocket, buffer, sizeof(buffer) - 1, 0);

        if (bytesReceived <= 0) {
            std::cout << "Server is GONE" << std::endl;
            break;
        }

        std::cout << buffer;
    }
    close(clientSocket);
    return 0;
}