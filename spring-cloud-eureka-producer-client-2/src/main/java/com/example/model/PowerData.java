package com.example.model;

public class PowerData {
    String timestamp;
    int real;
    int pred;

    public int getReal() {
        return real;
    }

    public void setReal(int real) {
        this.real = real;
    }

    public int getPred() {
        return pred;
    }

    public void setPred(int pred) {
        this.pred = pred;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
}
