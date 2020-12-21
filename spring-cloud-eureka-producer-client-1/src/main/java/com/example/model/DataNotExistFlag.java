package com.example.model;

public class DataNotExistFlag {
    boolean flag = false;
    public DataNotExistFlag(boolean b){
        flag = b;
    }

    public boolean getFlag() {
        return flag;
    }

    public void setFlag(boolean flag) {
        this.flag = flag;
    }
}
