package com.example.model;

import java.util.List;

public class ResponsePowerEvaluateJson extends ResponseJsonTemplate{
    protected long timeStamp;
    protected String hostname;
    protected int realPower;
    protected List<VMData> vmlist;

    public long getTimeStamp() {
        return timeStamp;
    }

    public void setTimeStamp(long timeStamp) {
        this.timeStamp = timeStamp;
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public int getRealPower() {
        return realPower;
    }

    public void setRealPower(int realPower) {
        this.realPower = realPower;
    }

    public List<VMData> getVmlist() {
        return vmlist;
    }

    public void setVmlist(List<VMData> vmlist) {
        this.vmlist = vmlist;
    }
}
