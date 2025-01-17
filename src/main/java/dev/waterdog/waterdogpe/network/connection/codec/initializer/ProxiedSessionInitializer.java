/*
 * Copyright 2022 WaterdogTEAM
 * Licensed under the GNU General Public License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.waterdog.waterdogpe.network.connection.codec.initializer;

import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.network.connection.codec.batch.BedrockBatchDecoder;
import dev.waterdog.waterdogpe.network.connection.codec.batch.BedrockBatchEncoder;
import dev.waterdog.waterdogpe.network.connection.codec.batch.FrameIdCodec;
import dev.waterdog.waterdogpe.network.connection.codec.compression.*;
import dev.waterdog.waterdogpe.network.connection.codec.packet.BedrockPacketCodec;
import dev.waterdog.waterdogpe.network.connection.codec.packet.BedrockPacketCodec_v1;
import dev.waterdog.waterdogpe.network.connection.codec.packet.BedrockPacketCodec_v2;
import dev.waterdog.waterdogpe.network.connection.codec.packet.BedrockPacketCodec_v3;
import dev.waterdog.waterdogpe.network.connection.peer.ProxiedBedrockPeer;
import dev.waterdog.waterdogpe.network.connection.peer.ProxiedBedrockSession;
import io.netty.channel.*;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.common.util.Zlib;

@Log4j2
@AllArgsConstructor
public abstract class ProxiedSessionInitializer<T extends ProxiedBedrockSession> extends ChannelInitializer<Channel> {
    public static final FrameIdCodec<RakMessage> RAKNET_FRAME_CODEC = FrameIdCodec.RAK_CODEC.apply(0xfe);
    public static final BedrockBatchDecoder BATCH_DECODER = new BedrockBatchDecoder();

    protected final ProxyServer proxy;

    @Override
    protected void initChannel(Channel channel) {
        int rakVersion = channel.config().getOption(RakChannelOption.RAK_PROTOCOL_VERSION);

        channel.pipeline()
                .addLast(FrameIdCodec.NAME, RAKNET_FRAME_CODEC)
                .addLast(CompressionCodec.NAME, getCompressionCodec(this.proxy.getConfiguration().getCompression(), rakVersion, true))
                .addLast(BedrockBatchDecoder.NAME, BATCH_DECODER)
                .addLast(BedrockBatchEncoder.NAME, new BedrockBatchEncoder())
                .addLast(BedrockPacketCodec.NAME, getPacketCodec(rakVersion))
                .addLast(BedrockPeer.NAME, new ProxiedBedrockPeer(channel, this::createSession));
    }

    protected final T createSession(BedrockPeer peer, int subClientId) {
        T session = this.createSession0(peer, subClientId);
        this.initSession(session);
        return session;
    }

    protected abstract T createSession0(BedrockPeer peer, int subClientId);

    protected abstract void initSession(T session);

    public static BedrockPacketCodec getPacketCodec(int rakVersion) {
        return switch (rakVersion) {
            case 7 -> new BedrockPacketCodec_v1();
            case 8 -> new BedrockPacketCodec_v2();
            case 9, 10, 11 -> new BedrockPacketCodec_v3();
            default -> throw new UnsupportedOperationException("Unsupported RakNet protocol version: " + rakVersion);
        };
    }

    public static ChannelHandler getCompressionCodec(CompressionAlgorithm algorithm, int rakVersion, boolean initial) {
        return switch (rakVersion) {
            case 7, 8, 9 -> new ZlibCompressionCodec(Zlib.DEFAULT);
            case 10 -> new ZlibCompressionCodec(Zlib.RAW);
            case 11 -> initial ? new NoopCompressionCodec() : getCompressionCodec(algorithm);
            default -> throw new UnsupportedOperationException("Unsupported RakNet protocol version: " + rakVersion);
        };
    }

    private static ChannelHandler getCompressionCodec(CompressionAlgorithm algorithm) {
        if (algorithm == CompressionAlgorithm.ZLIB) {
            return new ZlibCompressionCodec(Zlib.RAW);
        } else if (algorithm == CompressionAlgorithm.SNAPPY) {
            return new SnappyCompressionCodec();
        } else {
            throw new UnsupportedOperationException("Unsupported compression algorithm: " + algorithm);
        }
    }
}
