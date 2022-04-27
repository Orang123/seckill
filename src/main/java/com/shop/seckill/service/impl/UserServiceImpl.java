package com.shop.seckill.service.impl;

import com.shop.seckill.dao.UserMapper;
import com.shop.seckill.dao.UserPasswordMapper;
import com.shop.seckill.error.BusinessException;
import com.shop.seckill.error.EmBusinessError;
import com.shop.seckill.pojo.User;
import com.shop.seckill.pojo.UserPassword;
import com.shop.seckill.service.UserService;
import com.shop.seckill.service.model.UserModel;
import com.shop.seckill.validator.ValidationResult;
import com.shop.seckill.validator.ValidatorImpl;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserServiceImpl implements UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserPasswordMapper userPasswordMapper;

    @Autowired
    private ValidatorImpl validator;

    @Override
    public UserModel getUserById(Integer id) {
        User user = userMapper.selectByPrimaryKey(id);
        if(user == null) {
            return null;
        }
        //通过用户id获取对应的用户加密密码信息
        UserPassword userPassword = userPasswordMapper.selectByUserId(id);
        return convertFromPojo(user, userPassword);
    }

    @Override
    @Transactional//注册插入2条数据 一个用户 一个用户密码 要么全部成功 要么全部失败
    public void register(UserModel userModel) throws BusinessException {
        if(userModel == null) {
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR);
        }
        ValidationResult result = validator.validate(userModel);
        if(result.isHasError()) {
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,result.getErrorMsg());
        }
        User user = convertFromModel(userModel);
        try{
            userMapper.insertSelective(user);//这里只插入 部分 是因为 如果某个属性为空,就不会插入 数据库表字段会使用默认值,这个主要用于更新
        }catch(DuplicateKeyException e){
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"手机号不能重复注册");
        }
        userModel.setId(user.getId());
        UserPassword userPassword = convertPasswordFromModel(userModel);
        userPasswordMapper.insertSelective(userPassword);
    }

    @Override
    public UserModel validateLogin(String telphone, String encrptPassword) throws BusinessException {
        //通过用户的手机获取用户信息
        User user = userMapper.selectByTelphone(telphone);
        if(user == null){
            throw new BusinessException(EmBusinessError.USER_LOGIN_FAIL);
        }
        UserPassword userPassword = userPasswordMapper.selectByUserId(user.getId());
        UserModel userModel = convertFromPojo(user,userPassword);

        //比对用户信息内加密的密码是否和传输进来的密码相匹配
        if(!StringUtils.equals(encrptPassword,userModel.getEncrptPassword())){
            throw new BusinessException(EmBusinessError.USER_LOGIN_FAIL);
        }
        return userModel;
    }

    //将user pojo对象转化为附带密码的userModel
    private UserModel convertFromPojo(User user, UserPassword userPassword) {
        UserModel userModel = new UserModel();
        //把user中的同名属性注入userModel
        BeanUtils.copyProperties(user, userModel);
        if(userPassword != null) {
            userModel.setEncrptPassword(userPassword.getEncrptPassword());
        }
        return userModel;
    }

    private User convertFromModel(UserModel userModel) {
        if(userModel == null) {
            return null;
        }
        User user = new User();
        BeanUtils.copyProperties(userModel, user);
        return user;
    }

    private UserPassword convertPasswordFromModel(UserModel userModel) {
        if(userModel == null) {
            return null;
        }
        UserPassword userPassword = new UserPassword();
        userPassword.setEncrptPassword(userModel.getEncrptPassword());
        userPassword.setUserId(userModel.getId());
        return userPassword;
    }

}
