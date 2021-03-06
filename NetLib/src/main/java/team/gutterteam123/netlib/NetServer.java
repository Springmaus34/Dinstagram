package team.gutterteam123.netlib;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import team.gutterteam123.baselib.util.ThreadUtil;
import team.gutterteam123.netlib.packetbase.Packet;

import java.util.concurrent.ThreadFactory;

@EqualsAndHashCode
public abstract class NetServer<P extends Packet> extends Thread {

    private EventLoopGroup bossGroup, workerGroup;
    private Channel channel;

    protected abstract void onClose(ChannelFuture future);
    protected abstract void onStart(ChannelFuture future);
    protected abstract void onChannelCreation(ChannelPipeline pipeline);
    protected abstract String getDisplayName();

    @Getter protected int port;
    protected boolean epoll;

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    private boolean stopping = false;

    public NetServer(int port) {
        this(port, Epoll.isAvailable());
    }

    public NetServer(int port, boolean epoll) {
        this.port = port;
        this.epoll = epoll;
    }

    @Getter @Setter protected boolean keepAlive = true;
    @Getter @Setter protected boolean autoReconnect = true;

    @Override
    public void run() {
        try {
            Thread.currentThread().setName(getDisplayName() + " Server Thread");
            logger.info("Starting {} under port {}...", getDisplayName(), port);

            ThreadFactory factory = ThreadUtil.getThreadFactory(getDisplayName() + " worker group #%s");

            bossGroup = epoll ? new EpollEventLoopGroup(0, factory) : new NioEventLoopGroup(0, factory);
            workerGroup = epoll ? new EpollEventLoopGroup(0, factory) : new NioEventLoopGroup(0, factory);

            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(epoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) throws Exception {
                            ChannelPipeline pipeline = channel.pipeline();
                            onChannelCreation(pipeline);
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 50)
                    .childOption(ChannelOption.SO_KEEPALIVE, keepAlive);
            channel = bootstrap.bind(port).sync().channel();
            ChannelFuture future = channel.closeFuture();
            future.addListener((ChannelFutureListener) (channelFuture) -> {
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
            });
            future.addListener((ChannelFutureListener) this::onClose);
            onStart(future);
            logger.info("Started {} under port {}!", getDisplayName(), port);
            future.sync();
        } catch (Exception ex) {
            logger.error("Error in NetServer", ex);
        }

        if (stopping) return;

        if (autoReconnect) {
            logger.info("{} Server is down! Restarting in 500ms", getDisplayName());
            ThreadUtil.sleep(500);
            run();
        } else {
            logger.info("{} Server is down! No Reconnecting!", getDisplayName());
        }
    }

    public void shutdown() {
        stopping = true;
        logger.info("Server {} is shutting down", getDisplayName());
        channel.close();
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

}
