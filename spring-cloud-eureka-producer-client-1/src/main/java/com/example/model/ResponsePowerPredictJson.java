package com.example.model;

import java.util.List;

public class ResponsePowerPredictJson extends ResponseJsonTemplate{
    private PowerData data;

    public PowerData getData() {
        return data;
    }

    public void setData(PowerData data) {
        this.data = data;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getServer() {
        return server;
    }

    public void setServer(String server) {
        this.server = server;
    }
}
