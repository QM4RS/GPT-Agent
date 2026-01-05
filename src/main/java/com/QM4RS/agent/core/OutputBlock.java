package com.QM4RS.agent.core;

public class OutputBlock {
    public String file;
    public String action;     // REPLACE_RANGE / REPLACE_METHOD / REPLACE_CLASS / CREATE_FILE
    public String range;      // "start-end"
    public String target;     // FQCN or FQCN#method
    public String anchor;     // "after line N"
    public String note;
    public String code;       // raw code

    @Override
    public String toString() {
        return "OutputBlock{file='%s', action='%s'}".formatted(file, action);
    }
}
