package com.example.gRPC;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.powerevaluate.PowerEvaluateServiceGrpc;
import io.grpc.examples.powerevaluate.PowerEvaluateReply;
import io.grpc.examples.powerevaluate.PowerEvaluateRequest;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;


public class GRPCPowerEvaluateClient {

    private static final Logger logger = Logger.getLogger(GRPCPowerEvaluateClient.class.getName());
    private final ManagedChannel channel;
    private final PowerEvaluateServiceGrpc.PowerEvaluateServiceBlockingStub blockingStub;

    public GRPCPowerEvaluateClient(String host, int port) {
        this(ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build());
    }

    GRPCPowerEvaluateClient(ManagedChannel channel) {
        this.channel = channel;
        blockingStub = PowerEvaluateServiceGrpc.newBlockingStub(channel);
    }

    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    public String evaluate(String host,String targetTimestamp,String algorithm) {
        System.out.println("Will try to predict " + host + " in " +targetTimestamp);
        PowerEvaluateRequest request = PowerEvaluateRequest.newBuilder()
                .setHost(host).setTargetTimestamp(targetTimestamp).setAlgorithm(algorithm).build();
        PowerEvaluateReply response;
        try {
            response = blockingStub.powerEvaluate(request);
        } catch (StatusRuntimeException e) {
            logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
            return "error";
        }
        //logger.info("Greeting: " + response.getMessage());
        System.out.println("服务端返回结果："+response.getPower());
        return response.getPower();
    }
}


