package com.shop.seckill.controller.viewobject;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ItemVo {

    private Integer id;

    //商品名称
    private String title;

    //商品价格
    private BigDecimal price;

    //商品的库存
    private Integer stock;

    //商品的描述
    private String description;

    //商品的销量
    private Integer sales;

    //商品描述图片的url
    private String imgUrl;

    //记录商品是否在秒杀活动中，以及对应的状态0：表示没有秒杀活动，1表示秒杀活动待开始，2表示秒杀活动进行中
    private Integer promoStatus;

    //秒杀活动价格
    private BigDecimal promoPrice;

    //秒杀活动ID 若秒杀活动不存在 promoId是为null的
    private Integer promoId;

    //秒杀活动开始时间 这里用String是因为展示给前端的json无法解析 java joda-time的DateTime模型
    private String startDate;

    //秒杀活动的结束时间 前端秒杀活动进行中 会不断判断当前时间和结束时间 避免秒杀活动结束,用户还能下单
    private String endDate;

}
