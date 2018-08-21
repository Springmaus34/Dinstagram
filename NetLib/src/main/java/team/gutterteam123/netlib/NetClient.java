package team.gutterteam123.netlib;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.util.concurrent.Future;
import lombok.Getter;
import lombok.Setter;
import team.gutterteam123.netlib.packetbase.Packet;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

public abstract class NetClient<P extends Packet> {
    
    protected InetSocketAddress address;
    protected boolean epoll;

    public NetClient(InetSocketAddress address, boolean epoll) {
        this.address = address;
        this.epoll = epoll;
    }

    public NetClient(InetSocketAddress address) {
        this(address, Epoll.isAvailable());
    }

    private EventLoopGroup workerGroup;
    private Channel channel;

    @Getter @Setter protected boolean keepAlive = true;
    @Getter @Setter protected boolean autoReconnect = true;
    @Getter @Setter protected float reconnectMulti = 1.5f;
    @Getter @Setter protected long reconnectMax = 30 * 1000;
    @Getter @Setter protected long reconnectStart = 5 * 1000;

    private long currentReconnect = -1;

    protected abstract void onClose(Future future);
    protected abstract void onChannelCreation(ChannelPipeline pipeline);
    protected abstract String getName();

    public void startServer() {
        workerGroup = epoll ? new EpollEventLoopGroup() : new NioEventLoopGroup();

        try {
            Bootstrap bootstrap = new Bootstrap()
                    .group(workerGroup)
                    .channel(epoll ? EpollSocketChannel.class : NioSocketChannel.class)
                    .option(ChannelOption.SO_KEEPALIVE, keepAlive)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socketChannel) throws Exception {
                            ChannelPipeline pipeline = socketChannel.pipeline();
                            onChannelCreation(pipeline);
                        }
                    });

            channel = bootstrap.connect(address).sync().channel();
            ChannelFuture f = channel.closeFuture().addListener(future -> workerGroup.shutdownGracefully());
            f.addListener(this::onClose);
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }

        if (autoReconnect) {
            if (currentReconnect == -1) {
                currentReconnect = reconnectStart;
            } else {
                currentReconnect *= reconnectMulti;
            }
            long reconnectDelay = Math.max(reconnectMax, currentReconnect);
            System.out.println(getName() + " Client is down! Reconnecting in " + reconnectDelay / 1000 + "secs!");
            try {
                Thread.sleep(reconnectDelay);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            startServer();
        }
    }



}