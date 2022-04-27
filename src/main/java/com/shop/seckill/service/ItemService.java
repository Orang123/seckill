package com.shop.seckill.service;

import com.shop.seckill.error.BusinessException;
import com.shop.seckill.service.model.ItemModel;

import java.util.List;

public interface ItemService {

    //创建商品
    ItemModel createItem(ItemModel itemModel) throws BusinessException;

    //商品详情浏览
    ItemModel getItemById(Integer id);

    //因为秒杀时刻下对同一商品下订单是个高频操作,需要将itemModel缓存在redis中,提高查询的性能,缓解服务器的压力
    //这里ItemController实际也缓存了商品详情,但是尽量避免在下单的时候ItemModel用之前获取商品详情中的itemModel,应该单独设置一个商品的itemModel
    //因为下单时的商品的某些状态可能和一开始获取商品详情页的状态信息不一致
    ItemModel getItemByIdIncache(Integer id);

    //商品列表浏览
    List<ItemModel> listItem();

    //库存扣减 只是减少redis中的库存
    boolean decreaseStock(Integer itemId, Integer amount);

    //异步扣减库存 真正通过mq异步扣减mysql中的库存 削峰 降低同一时间访问数据库的压力 负载均衡 这个实现后来放到了MqProducer中
    //因为要保证异步发送消息扣减数据库库存 是在创建订单事务全部成功的条件下,以及生产者发送消息成功 ItemService中的方法被遗弃
//    boolean asyncDecreaseStock(Integer itemId, Integer amount);

    //库存回补 decreaseStock方法redis中库存不足时 回补redis中的库存
    boolean increaseStock(Integer itemId, Integer amount);

    //商品销量增加
    void increaseSales(Integer itemId, Integer amount);

    //初始化库存流水
    String initStockLog(Integer itemId, Integer amount);

}
