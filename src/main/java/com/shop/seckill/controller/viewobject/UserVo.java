package com.shop.seckill.controller.viewobject;

import lombok.Data;

//对于展示给前端用户的数据 registerMode thirdPartyId encrptPassword是不需要的
@Data
public class UserVo {

    private Integer id;

    private String name;

    private Byte gender;

    private Integer age;

    private String telphone;

}
