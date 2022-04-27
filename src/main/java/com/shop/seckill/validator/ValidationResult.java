package com.shop.seckill.validator;

import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Map;

public class ValidationResult {
    //校验结果是否有错 默认初始化没有错误
    private boolean hasErrorr = false;

    //存放错误信息的map key-value对应 属性名字->错误信息
    private Map<String, Object> errorMsgMap = new HashMap<>();

    public boolean isHasError() {
        return hasErrorr;
    }

    public void setHasError(boolean hasErrorr) {
        this.hasErrorr = hasErrorr;
    }

    public Map<String, Object> getErrorMsgMap() {
        return errorMsgMap;
    }

    public void setErrorMsgMap(Map<String,Object> errorMsgMap) {
        this.errorMsgMap = errorMsgMap;
    }

    //实现通用的通过格式化字符串信息获取错误结果的msg方法 把model实体的属性所有错误信息errorMsgMap用,号分割开返回成String
    public String getErrorMsg() {
        return StringUtils.join(errorMsgMap.values().toArray(),',');
    }

}
