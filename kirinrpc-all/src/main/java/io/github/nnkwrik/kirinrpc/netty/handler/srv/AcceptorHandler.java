package io.github.nnkwrik.kirinrpc.netty.handler.srv;

import io.github.nnkwrik.kirinrpc.common.util.StackTraceUtil;
import io.github.nnkwrik.kirinrpc.netty.IdealStateException;
import io.github.nnkwrik.kirinrpc.netty.model.RequestPayload;
import io.github.nnkwrik.kirinrpc.rpc.provider.RequestProcessor;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderException;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author nnkwrik
 * @date 19/05/18 15:13
 */
@Slf4j
@ChannelHandler.Sharable
public class AcceptorHandler extends ChannelInboundHandlerAdapter {

    private final RequestProcessor processor;


    public AcceptorHandler(RequestProcessor processor) {
        this.processor = processor;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        Channel ch = ctx.channel();

        if (msg instanceof RequestPayload) {
            try {
                processor.handleRequest(ch, (RequestPayload) msg);
            } catch (Throwable t) {
                processor.handleException(ch, (RequestPayload) msg, t);
            }
        } else {
            log.warn("Unexpected message type received: {}, channel: {}.", msg.getClass(), ch);

            ReferenceCountUtil.release(msg);
        }
    }

    private static final AtomicInteger channelCounter = new AtomicInteger(0);

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {

        int count = channelCounter.incrementAndGet();

        log.info("Connects with {} as the {}th channel.", ctx.channel(), count);

        super.channelInactive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        int count = channelCounter.getAndDecrement();

        log.warn("Disconnects with {} as the {}th channel.", ctx.channel(), count);

        super.channelInactive(ctx);
    }


    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        Channel ch = ctx.channel();

        if (cause instanceof IdealStateException) {
            log.error("IdealState exception exception was caught, force to close channel: {}.\r\n{}", ch, StackTraceUtil.stackTrace(cause));

            ch.close();
        } else if (cause instanceof IOException) {
            log.error("An I/O exception was caught, force to close channel: {}.\r\n{}", ch, StackTraceUtil.stackTrace(cause));

            ch.close();
        } else if (cause instanceof DecoderException) {
            log.error("Decoder exception was caught, force to close channel: {}.\r\n{}", ch, StackTraceUtil.stackTrace(cause));
            ch.close();
        } else {
            log.error("Unexpected exception was caught, channel: {}.\r\n{}", ch, StackTraceUtil.stackTrace(cause));
        }

    }
}
