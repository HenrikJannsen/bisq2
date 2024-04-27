package b2.controllers.network;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

public class P2PService extends Thread {

    public void run() {
        int port = 2140; // Port to listen on
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("p2p service listening at bisq://localhost:" + port);

            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("New p2p client connected");

                // Handle each client in a new thread
                new ClientHandler(socket).start();
            }

        } catch (IOException ex) {
            System.out.println("P2P service exception: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    // Thread class to handle each client connection
    private static class ClientHandler extends Thread {
        private Socket socket;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        public void run() {
            try (InputStream input = socket.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input));
                 OutputStream output = socket.getOutputStream();
                 PrintWriter writer = new PrintWriter(output, true)) {

                String text;

                // Read client input until "bye" is received
                while ((text = reader.readLine()) != null) {
                    System.out.println("Received: " + text);
                    writer.println("Echo: " + text);

                    if ("bye".equalsIgnoreCase(text)) {
                        System.out.println("Client terminated connection");
                        break;
                    }
                }

                socket.close();

            } catch (IOException ex) {
                System.out.println("P2P service exception: " + ex.getMessage());
                ex.printStackTrace();
            }
        }
    }
}
