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

package bisq.network.p2p.node.envelope;

import bisq.common.network.PeerSocket;
import bisq.network.p2p.message.NetworkEnvelope;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
public class NetworkEnvelopeSocket implements Closeable {
    // 2.25 MB is our limit. Inventory requests are capped to about 2 MB
    private final static int MAX_ALLOWED_SIZE = 2_250_000;
    private final PeerSocket socket;
    private final InputStream inputStream;
    private final OutputStream outputStream;

    public NetworkEnvelopeSocket(PeerSocket socket) {
        this.socket = socket;
        this.inputStream = socket.getInputStream();
        this.outputStream = socket.getOutputStream();
    }

    public void send(NetworkEnvelope networkEnvelope) throws IOException {
        networkEnvelope.writeDelimitedTo(outputStream);
        outputStream.flush();
    }

    public bisq.network.protobuf.NetworkEnvelope receiveNextEnvelope() throws IOException {
        try {
            int firstByte = inputStream.read();
            if (firstByte == -1) {
                // If EOF we return early
                return null;
            }
            int size = CodedInputStream.readRawVarint32(firstByte, inputStream);
            checkArgument(size > 0, "Size of protobuf message must not be 0");
            checkArgument(size <= MAX_ALLOWED_SIZE, "Size of protobuf message exceeds our limit. size=" + size);
            CodedInputStream codedInput = CodedInputStream.newInstance(inputStream);
            codedInput.pushLimit(size);
            codedInput.setRecursionLimit(20); // TODO with limit of 7 we can observe exceptions. Not sure which message causes that recursion
            return bisq.network.protobuf.NetworkEnvelope.parseFrom(codedInput);
        } catch (IOException e) {
            throw new InvalidProtocolBufferException(e);
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    public boolean isClosed() {
        return socket.isClosed();
    }
}
