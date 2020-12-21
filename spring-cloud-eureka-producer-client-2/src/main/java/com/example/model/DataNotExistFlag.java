package com.example.commonutils;

public class DataNotExistFlag {
    boolean flag = true;
    public DataNotExistFlag(boolean b){
        flag = b;
    }

    public boolean isFlag() {
        return flag;
    }

    public void setFlag(boolean flag) {
        this.flag = flag;
    }
}
