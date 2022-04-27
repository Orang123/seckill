package com.shop.seckill.controller;

import com.shop.seckill.controller.viewobject.UserVo;
import com.shop.seckill.error.BusinessException;
import com.shop.seckill.error.EmBusinessError;
import com.shop.seckill.response.CommonReturnType;
import com.shop.seckill.service.UserService;
import com.shop.seckill.service.model.UserModel;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import sun.misc.BASE64Encoder;

import javax.servlet.http.HttpServletRequest;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/user")
//允许跨域 因为前端页面并不在当前项目的templates,因此ajax请求会报异常,允许跨域即可
//allowCredentials设置为true是因为
//DEFAULT_ALLOWED_HEADERS:允许跨域传输所有的header参数,将用于使用token放入header域做session共享的跨域请求
//这里谷歌浏览器暂时不能解决跨域session不能共享的的问题,火狐可以,或者将springboot升级至高版本,在properties中配置
// server.servlet.session.cookie.same-site=none
//server.servlet.session.cookie.secure=true 即可 低版本没有same-site这个属性
@CrossOrigin(allowCredentials="true", allowedHeaders = "*")
public class UserController extends BaseController{

    @Autowired
    private UserService userService;

    @Autowired//这里并不是单例的request对象,底层是用threadlocal实现的线程隔离,所以虽然是自动注入,但是每个用户拿到的HttpServletRequest都是当前线程独有的
    private HttpServletRequest httpServletRequest;

    @Autowired
    private RedisTemplate redisTemplate;

    @RequestMapping(path = "/get/{id}", method = RequestMethod.GET)
    public CommonReturnType getUser(@PathVariable("id")Integer id) throws BusinessException {
        UserModel userModel = userService.getUserById(id);
        if(userModel == null) {
            throw new BusinessException(EmBusinessError.USER_NOT_EXIST);
        }
        //讲核心领域模型用户对象转化为可供UI使用的viewobject
        UserVo userVo = convertFromModel(userModel);
        //返回通用对象
        return CommonReturnType.create(userVo);
    }

    private UserVo convertFromModel(UserModel userModel) {
        UserVo userVo = new UserVo();
        BeanUtils.copyProperties(userModel, userVo);
        return userVo;
    }

    //用户获取otp短信接口 这只是做一个简单的demo 实际需要用阿里云的短信服务，用于给手机发送验证码
    //opt验证码 动态口令（OTP，One-Time Password）又称一次性密码
    //consumes表示表单post提交为"application/x-www-form-urlencoded"方式,如果异步没有file input 就用这种方式
    @RequestMapping(path = "/getotp",method = RequestMethod.POST,consumes = CONTENT_TYPE_FORMED)
    public CommonReturnType getOtp(String telphone) {
        //需要按照一定的规则生成OTP验证码
        Random random = new Random();
        int randomInt = random.nextInt(99999);
        randomInt += 10000;
        String otpCode = String.valueOf(randomInt);
        //将OTP验证码同对应用户的手机号关联，使用httpsession的方式绑定他的手机号与OTPCODE
        httpServletRequest.getSession().setAttribute(telphone, otpCode);
        //将OTP验证码通过短信通道发送给用户,省略
        System.out.println("telphone = " + telphone + " & otpCode = "+otpCode);
        return CommonReturnType.create(null);
    }

    @RequestMapping(path = "/register",method = RequestMethod.POST,consumes = CONTENT_TYPE_FORMED)
    //这里参数列表其实可以直接写一个UserModel接受就好 不用再new 再set,一开始以为model模型能干啥别的 就跟着视频里的写了...结果发现也只是转换一下
    public CommonReturnType register(String telphone, String otpCode, String name, Integer gender, Integer age, String password) throws BusinessException, UnsupportedEncodingException, NoSuchAlgorithmException {
        //验证手机号和对应的otpcode相符合
        String inSessionOtpCode = (String) this.httpServletRequest.getSession().getAttribute(telphone);
        if(!StringUtils.equals(otpCode,inSessionOtpCode)){
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"短信验证码不符合");
        }
        //用户的注册流程
        UserModel userModel = new UserModel();
        userModel.setName(name);
        userModel.setGender(new Byte(String.valueOf(gender.intValue())));
        userModel.setAge(age);
        userModel.setTelphone(telphone);
        userModel.setRegisterMode("byphone");
        userModel.setEncrptPassword(this.EncodeByMd5(password));
        //这里没有设置第三方账号id
        userService.register(userModel);
        return CommonReturnType.create(null);
    }

    private String EncodeByMd5(String str) throws NoSuchAlgorithmException, UnsupportedEncodingException {
        //确定计算方法
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        BASE64Encoder base64en = new BASE64Encoder();
        //加密字符串
        String newstr = base64en.encode(md5.digest(str.getBytes("utf-8")));
        return newstr;
    }

    @RequestMapping(value = "/login",method = {RequestMethod.POST},consumes={CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType login(String telphone, String password) throws BusinessException, UnsupportedEncodingException, NoSuchAlgorithmException {
        //入参校验
        if(StringUtils.isEmpty(telphone)||
                StringUtils.isEmpty(password)){
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR);
        }

        //用户登陆服务,用来校验用户登陆是否合法
        UserModel userModel = userService.validateLogin(telphone,this.EncodeByMd5(password));
        //将登陆凭证加入到用户登陆成功的session内
//        this.httpServletRequest.getSession().setAttribute("IS_LOGIN",true);
//        this.httpServletRequest.getSession().setAttribute("LOGIN_USER",userModel);
        //修改成若用户登录验证成功后将对应的登录信息和登录凭证一起存入redis中
        String uuidToken = UUID.randomUUID().toString();
        uuidToken = uuidToken.replace("-","");
        //将userModel存入以key为uuidToken的键值对存入redis中 过期时间1个小时
        redisTemplate.opsForValue().set(uuidToken,userModel,1, TimeUnit.HOURS);
        return CommonReturnType.create(uuidToken);
    }

}
