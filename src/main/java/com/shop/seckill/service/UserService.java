package com.shop.seckill.service;

import com.shop.seckill.error.BusinessException;
import com.shop.seckill.pojo.User;
import com.shop.seckill.service.model.UserModel;

public interface UserService {

    UserModel getUserById(Integer id);

    void register(UserModel userModel) throws BusinessException;

    UserModel validateLogin(String telphone,String encrptPassword) throws BusinessException;

}
