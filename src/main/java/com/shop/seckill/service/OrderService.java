package com.shop.seckill.service;

import com.shop.seckill.error.BusinessException;
import com.shop.seckill.service.model.OrderModel;

public interface OrderService {

    //使用1,通过前端url上传过来秒杀活动id，然后下单接口内校验对应id是否属于对应商品且活动已开始
    //2.直接在下单接口内判断对应的商品是否存在秒杀活动，若存在进行中的则以秒杀价格下单
    //推荐第一种方案,因为一种商品可能存在多种秒杀活动,需要获取用户通过哪个app进入,知道其访问路径
    OrderModel createOrder(Integer userId, Integer itemId, Integer promoId,Integer amount, String stockLogId) throws BusinessException;

}
