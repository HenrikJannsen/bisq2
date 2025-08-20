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

package bisq.network.p2p.node.netty.p2p.handshake;

import bisq.network.protobuf.Envelope;
import bisq.network.protobuf.Handshake;
import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;


@Slf4j
public class InboundHandshakeHandler extends HandshakeHandler {
    public InboundHandshakeHandler(Handler handler) {
      super(handler);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Envelope envelope) {
        envelope.getHandshakeRequest();
        if (envelope.hasHandshakeRequest()) {
            Handshake.Request request = envelope.getHandshakeRequest();

            Handshake.Response response = Handshake.Response.newBuilder().setAccepted(true).build();
            Envelope env = Envelope.newBuilder()
                    .setHandshakeResponse(response)
                    .build();
            ctx.writeAndFlush(env);
            handler.onHandshakeCompleted(ctx.channel());
        }
    }
}

