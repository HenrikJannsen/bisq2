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
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;


@Slf4j
public abstract class HandshakeHandler extends SimpleChannelInboundHandler<Envelope> {
    public interface Handler {
        void onHandshakeCompleted(Channel channel);

        void onClosed(Channel channel);
    }

    protected final Handler handler;

    public HandshakeHandler(Handler handler) {
        this.handler = handler;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        handler.onClosed(ctx.channel());
    }
}

