package io.github.nnkwrik.kirinrpc.rpc.provider;

import io.github.nnkwrik.kirinrpc.common.util.StackTraceUtil;
import io.github.nnkwrik.kirinrpc.netty.model.RequestPayload;
import io.github.nnkwrik.kirinrpc.netty.model.ResponsePayload;
import io.github.nnkwrik.kirinrpc.netty.protocol.Status;
import io.github.nnkwrik.kirinrpc.rpc.KirinRemoteException;
import io.github.nnkwrik.kirinrpc.rpc.model.KirinResponse;
import io.github.nnkwrik.kirinrpc.serializer.Serializer;
import io.github.nnkwrik.kirinrpc.serializer.SerializerHolder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author nnkwrik
 * @date 19/05/18 15:48
 */
@Slf4j
public class ProviderProcessor implements RequestProcessor {

    private static ThreadPoolExecutor executor;

    private final ResponseSender responseSender;

    private final ProviderLookup providerLookup;

    public ProviderProcessor(ProviderLookup providerLookup) {
        this.providerLookup = providerLookup;
        this.responseSender = new ResponseSenderImpl();
    }


    @Override
    public void handleRequest(Channel channel, RequestPayload requestPayload) throws Exception {
        ProviderTask task = new ProviderTask(channel, requestPayload, responseSender, providerLookup);

        submit(task);
    }

    @Override
    public void handleException(Channel channel, RequestPayload requestPayload, Throwable cause) {
        log.error("Handling exception (requestId = {}).", requestPayload.id());

        String msg = "Unknown Error happened when solve remote call";
        responseSender.sendErrorResponse(channel, requestPayload.id(), requestPayload.timestamp(),
                new KirinRemoteException(msg, cause, Status.SERVICE_UNEXPECTED_ERROR));

    }

    @Override
    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
        }
    }


    private static void submit(Runnable task) {
        if (executor == null) {
            synchronized (ProviderProcessor.class) {
                if (executor == null) {
                    //双重锁创建线程池
                    executor = new ThreadPoolExecutor(16, 16, 600L,
                            TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(65536));
                }
            }
        }
        executor.execute(task);
    }


    public static class ResponseSenderImpl implements ResponseSender {

        private Serializer serializer = SerializerHolder.serializerImpl();

        @Override
        public void sendSuccessResponse(Channel channel, long requestId, long requestTime, Object invokeResult) {
            log.info("Success to invoke provider (requestId = {}), result = [{}].", requestId, invokeResult);
            KirinResponse response = new KirinResponse();
            response.setResult(invokeResult);

            ResponsePayload responsePayload = new ResponsePayload(requestId);
            responsePayload.timestamp(requestTime);
            responsePayload.status(Status.OK.value());
            byte[] bytes = serializer.writeObject(response);
            responsePayload.bytes(bytes);

            sendResponsePayload(channel, responsePayload);
        }

        @Override
        public void sendFailResponse(Channel channel, long requestId, long requestTime, KirinRemoteException e) {

            if (e.getStatus() == Status.SERVICE_UNEXPECTED_ERROR) {
                log.error("Status can't be SERVICE_UNEXPECTED_ERROR if you want to send fail response.So this response will process by #sendErrorResponse()");
                sendErrorResponse(channel, requestId, requestTime, e);
            }

            log.error("Excepted Error Happened when solve remote call (requestId = {}):\r\n{}",
                    requestId, StackTraceUtil.stackTrace(e));

            KirinResponse response = new KirinResponse();
            response.setError(e);

            ResponsePayload responsePayload = new ResponsePayload(requestId);
            responsePayload.timestamp(requestTime);
            responsePayload.status(e.getStatus().value());
            byte[] bytes = serializer.writeObject(response);
            responsePayload.bytes(bytes);

            sendResponsePayload(channel, responsePayload);
        }

        @Override
        public void sendErrorResponse(Channel channel, long requestId, long requestTime, KirinRemoteException e) {
            log.error("Unknown Error happened when solve remote call (requestId = {}):\r\n{}",
                    requestId, StackTraceUtil.stackTrace(e));

            KirinResponse response = new KirinResponse();
            e.setStatus(Status.SERVICE_UNEXPECTED_ERROR);//强制设为SERVICE_UNEXPECTED_ERROR
            response.setError(e);

            ResponsePayload responsePayload = new ResponsePayload(requestId);
            responsePayload.timestamp(requestTime);
            responsePayload.status(e.getStatus().value());
            byte[] bytes = serializer.writeObject(response);
            responsePayload.bytes(bytes);

            sendResponsePayload(channel, responsePayload, true);
        }

        private void sendResponsePayload(Channel channel, ResponsePayload responsePayload) {
            sendResponsePayload(channel, responsePayload, false);
        }

        private void sendResponsePayload(Channel channel, ResponsePayload responsePayload, boolean close) {
            if (responsePayload.status() == 0x00) {
                responsePayload.status(Status.OK.value());
            }

            channel.writeAndFlush(responsePayload).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture channelFuture) throws Exception {

                    if (channelFuture.isSuccess()) {
                        log.debug("Success to send response to request {},spent {} milliseconds during request",
                                responsePayload.id(), System.currentTimeMillis() - responsePayload.timestamp());
                    } else {
                        log.error("Fail to send response to request {},spent {} milliseconds during request",
                                responsePayload.id(), System.currentTimeMillis() - responsePayload.timestamp());
                        channel.close();
                        return;
                    }
                    if (close) {
                        log.debug("Close the channel (requestId = {}).", responsePayload.id());
                        channel.close();
                    }
                }
            });
        }

    }
}
