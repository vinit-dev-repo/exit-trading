package com.exittrading.app.dto;

import java.util.ArrayList;
import java.util.List;

public class ScreenerFilter {

    private String type;
    private String operator;
    private List<ScreenerFilter> children = new ArrayList<>();
    private String field;
    private String op;
    private Object value;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public List<ScreenerFilter> getChildren() {
        return children;
    }

    public void setChildren(List<ScreenerFilter> children) {
        this.children = children != null ? children : new ArrayList<>();
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getOp() {
        return op;
    }

    public void setOp(String op) {
        this.op = op;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }
}
