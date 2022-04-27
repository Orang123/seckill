package com.shop.seckill.service.model;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

/*
@NotNull适用于任何类型，被注解的元素必须不能是null
@NotEmpty适用于String，Collection，Map或者Array，不能为null或empty（从最短长度为1也能看出）
@NotEmpty的元素首先肯定是@NotNull的
@NotBlank只能用在String上
@NotBlank会忽略尾部的空格（如果只有空格，@NotEmpty认为不是empty的，而@NotBlank会认为是empty的）
@NotBlank与@NotEmpty的区别是：
@NotBlank只能用在String上
@NotBlank会忽略尾部的空格（如果只有空格，@NotEmpty认为不是empty的，而@NotBlank会认为是empty的）
@NotBlank的String首先肯定是@NotEmpty的
整数用@NotNull 字符串用@NotBlank,集合map set用@NotEmpty
* */

//这里userModel需要序列化 因为是将其存入redis的集中式缓存session,redis默认采用jdk序列化方式
@Data
public class UserModel implements Serializable {

    private Integer id;

    @NotBlank(message = "用户名不能为空")
    private String name;

    @NotNull(message = "性别不能不填写")
    private Byte gender;

    @NotNull(message = "年龄不能不填写")
    @Min(value = 0,message = "年龄必须大于0岁")
    @Max(value = 150,message = "年龄必须小于150岁")
    private Integer age;

    @NotBlank(message = "手机号不能为空")
    private String telphone;

    private String registerMode;

    private String thirdPartyId;

    @NotBlank(message = "密码不能为空")
    private String encrptPassword;

}
