package com.example.gRPC;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.powerpredict.PowerPredictReply;
import io.grpc.examples.powerpredict.PowerPredictRequest;
import io.grpc.examples.powerpredict.PowerPredictServiceGrpc;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;


public class GRPCPowerPredictClient {

    private static final Logger logger = Logger.getLogger(GRPCPowerPredictClient.class.getName());
    private final ManagedChannel channel;
    private final PowerPredictServiceGrpc.PowerPredictServiceBlockingStub blockingStub;

    public GRPCPowerPredictClient(String host, int port) {
        this(ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build());
    }

    GRPCPowerPredictClient(ManagedChannel channel) {
        this.channel = channel;
        blockingStub = PowerPredictServiceGrpc.newBlockingStub(channel);
    }

    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    public String greet(String host,String start,String end,String algorithm) {
        System.out.println("Will try to predict " + host + " between " +start + "and" + end);
        PowerPredictRequest request = PowerPredictRequest.newBuilder()
                .setHost(host).setStart(start).setEnd(end).setAlgorithm(algorithm).build();
        PowerPredictReply response;
        try {
            response = blockingStub.powerPredict(request);
        } catch (StatusRuntimeException e) {
            logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
            return "error";
        }finally {
            try {
                if (channel!=null) {
                    shutdown();
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        //logger.info("Greeting: " + response.getMessage());
        if (response==null){
            System.out.println("服务端未链接");
            return null;
        }
        System.out.println("服务端返回结果："+response.getPower());
        return response.getPower();
    }
}


