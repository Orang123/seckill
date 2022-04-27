package com.shop.seckill.controller;

import com.shop.seckill.error.BusinessException;
import com.shop.seckill.error.EmBusinessError;
import com.shop.seckill.response.CommonReturnType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

//作为各个Controller的通用异常处理Controller
public class BaseController {

    public static final String CONTENT_TYPE_FORMED="application/x-www-form-urlencoded";

    @ExceptionHandler
    /*如果没有写这个，浏览器会收到500状态码，但是服务器是响应了你的请求的，只不过没有找到对应的用户，也就是业务逻辑出了问题，但是，服务器是正确的响应了你的发的请求，不应该是500错误*/
    //实际就是跳过SpringBoot处理的500空白页异常,转为正常状态被捕获 自己处理业务异常
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public Object handleException(HttpServletRequest request, Exception ex) {
        Map<String, Object> responseData = new HashMap<>();
        if(ex instanceof BusinessException) {
            BusinessException businessException = (BusinessException)ex;
            responseData.put("errCode",businessException.getErrCode());
            responseData.put("errMsg",businessException.getErrMsg());
        } else {
            responseData.put("errCode", EmBusinessError.UNKNOWN_ERROR.getErrCode());
            responseData.put("errMsg", EmBusinessError.UNKNOWN_ERROR.getErrMsg());
        }
        return CommonReturnType.create("fail",responseData);
    }

}
