/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.network.p2p.node;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class NonBlockingServerWithHandler {
    private static final int PORT = 9000;
    private static final ByteBuffer buffer = ByteBuffer.allocate(256);
    private static final Random random = new Random();

    // Keep track of connected clients
    private static final Set<SocketChannel> clients = ConcurrentHashMap.newKeySet();

    public static void main(String[] args) throws IOException {
        Selector selector = Selector.open();

        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(PORT));
        serverChannel.configureBlocking(false);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        System.out.println("Server started on port " + PORT);

        // Schedule a periodic task to send random strings to clients
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            String msg = "Random: " + UUID.randomUUID().toString() + "\n";
            broadcast(msg);
        }, 2, 5, TimeUnit.SECONDS); // start after 2s, repeat every 5s

        while (true) {
            selector.select(); // block until at least one channel is ready
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();

            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();

                try {
                    if (key.isAcceptable()) {
                        handleAccept(key, selector);
                    } else if (key.isReadable()) {
                        handleRead(key);
                    }
                } catch (IOException e) {
                    key.cancel();
                    key.channel().close();
                }
            }
        }
    }

    private static void handleAccept(SelectionKey key, Selector selector) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        client.configureBlocking(false);
        client.register(selector, SelectionKey.OP_READ);
        clients.add(client);
        System.out.println("Client connected: " + client.getRemoteAddress());
    }

    private static void handleRead(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        buffer.clear();
        int read = client.read(buffer);

        if (read == -1) {
            System.out.println("Client disconnected: " + client.getRemoteAddress());
            clients.remove(client);
            client.close();
        } else if (read > 0) {
            buffer.flip();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            String message = new String(data).trim();

            // Print the message from client
            System.out.println("Received from " + client.getRemoteAddress() + ": " + message);
        }
    }

    private static void broadcast(String message) {
        ByteBuffer outBuffer = ByteBuffer.wrap(message.getBytes());
        for (SocketChannel client : clients) {
            try {
                client.write(outBuffer.duplicate());
            } catch (IOException e) {
                try {
                    client.close();
                } catch (IOException ignored) {
                }
            }
        }
        System.out.println("Broadcast: " + message.trim());
    }
}
