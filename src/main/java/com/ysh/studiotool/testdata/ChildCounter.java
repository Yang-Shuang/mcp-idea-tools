package com.ysh.studiotool.testdata;

public class ChildCounter extends BaseCounter {

    public int increment() {
        count = count + 1;
        return count;
    }
}
