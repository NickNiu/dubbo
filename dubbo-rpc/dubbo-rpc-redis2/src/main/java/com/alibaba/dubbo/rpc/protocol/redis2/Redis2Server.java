package com.alibaba.dubbo.rpc.protocol.redis2;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import redis.server.netty.RedisCommandDecoder;
import redis.server.netty.RedisCommandHandler;
import redis.server.netty.RedisReplyEncoder;

import java.util.concurrent.Executors;

/**
 * Created by wuyu on 2017/2/7.
 */
public class Redis2Server {

    private String host = "0.0.0.0";

    private int port = 6380;

    private EventLoopGroup bossGroup;

    private EventLoopGroup workerGroup;

    private EventLoopGroup redisGroup;

    private RpcRedisCommandHandler rpcRedisCommandHandler;

    private RpcSimpleRedisServer rpcSimpleRedisServer = new RpcSimpleRedisServer();

    public Redis2Server(String host, int port, int threads, int timeout) {
        this.host = host;
        this.port = port;
        workerGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors() + 1, Executors.newFixedThreadPool(threads));
        bossGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors() + 1);
        this.rpcRedisCommandHandler = new RpcRedisCommandHandler(rpcSimpleRedisServer);
        redisGroup = new NioEventLoopGroup(1);
    }

    public Redis2Server(String host, int port, int threads, int timeout, RpcRedisCommandHandler rpcRedisCommandHandler) {
        this.host = host;
        this.port = port;
        workerGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors() + 1, Executors.newFixedThreadPool(threads));
        bossGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors());
        this.rpcRedisCommandHandler = rpcRedisCommandHandler;
        redisGroup = new NioEventLoopGroup(1);
    }

    public void start() {
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.RCVBUF_ALLOCATOR, new AdaptiveRecvByteBufAllocator(64, 1024, 65536))
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.TCP_NODELAY, Boolean.TRUE)
                .childOption(ChannelOption.SO_KEEPALIVE, Boolean.TRUE)
                .childOption(ChannelOption.SO_SNDBUF, 1024)
                .childOption(ChannelOption.SO_RCVBUF, 1024)
                .childOption(ChannelOption.SO_REUSEADDR, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch)
                            throws Exception {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new RedisCommandDecoder());
                        p.addLast(new RedisReplyEncoder());
                        p.addLast(getRpcRedisCommandHandler());
                        p.addLast(redisGroup, new RedisCommandHandler(rpcSimpleRedisServer));
                    }
                });

        try {
            ChannelFuture f = bootstrap.bind(this.host, this.port).sync().channel().closeFuture();
        } catch (InterruptedException e) {
            e.printStackTrace();
            stop();
        }
    }

    public void stop() {
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        redisGroup.shutdownGracefully();
    }

    public RpcRedisCommandHandler getRpcRedisCommandHandler() {
        return rpcRedisCommandHandler;
    }
}
