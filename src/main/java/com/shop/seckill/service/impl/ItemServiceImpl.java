package com.shop.seckill.service.impl;

import com.shop.seckill.dao.ItemMapper;
import com.shop.seckill.dao.ItemStockMapper;
import com.shop.seckill.dao.StockLogMapper;
import com.shop.seckill.error.BusinessException;
import com.shop.seckill.error.EmBusinessError;
import com.shop.seckill.mq.MqProducer;
import com.shop.seckill.pojo.Item;
import com.shop.seckill.pojo.ItemStock;
import com.shop.seckill.pojo.StockLog;
import com.shop.seckill.service.ItemService;
import com.shop.seckill.service.PromoService;
import com.shop.seckill.service.model.ItemModel;
import com.shop.seckill.service.model.PromoModel;
import com.shop.seckill.validator.ValidationResult;
import com.shop.seckill.validator.ValidatorImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class ItemServiceImpl implements ItemService {

    @Autowired
    private ValidatorImpl validator;

    @Autowired
    private ItemMapper itemMapper;

    @Autowired
    private ItemStockMapper itemStockMapper;

    @Autowired
    private PromoService promoService;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private MqProducer mqProducer;

    @Autowired
    private StockLogMapper stockLogMapper;

    private Item convertItemFromItemModel(ItemModel itemModel) {
        if(itemModel == null) {
            return null;
        }
        Item item = new Item();
        BeanUtils.copyProperties(itemModel,item);
        item.setPrice(itemModel.getPrice().doubleValue());
        return item;
    }

    private ItemStock convertItemStockFromItemModel(ItemModel itemModel) {
        if(itemModel == null) {
            return null;
        }
        ItemStock itemStock = new ItemStock();
        itemStock.setStock(itemModel.getStock());
        itemStock.setItemId(itemModel.getId());
        return itemStock;
    }


    @Override
    @Transactional//标注事务,商品和商品库存应该同时插入,同时成功或同时失败
    public ItemModel createItem(ItemModel itemModel) throws BusinessException {
        ValidationResult result = validator.validate(itemModel);
        if(result.isHasError()) {
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, result.getErrorMsg());
        }
        //转化itemModel->Pojo
        Item item = convertItemFromItemModel(itemModel);
        itemMapper.insertSelective(item);
        itemModel.setId(item.getId());
        ItemStock itemStock = convertItemStockFromItemModel(itemModel);
        itemStockMapper.insertSelective(itemStock);
        return getItemById(item.getId());
    }

    //获取商品模型 将库存、秒杀活动、item聚合起来
    @Override
    public ItemModel getItemById(Integer id) {
        Item item = itemMapper.selectByPrimaryKey(id);
        if(item == null) {
            return null;
        }
        //获得库存数量
        ItemStock itemStock = itemStockMapper.selectByItemId(id);
        ItemModel itemModel = convertModelFromPojo(item, itemStock);
        //获取秒杀活动商品信息
        PromoModel promoModel = promoService.getPromoByItemId(id);
        //如果存在秒杀活动且秒杀活动尚未开始1或进行中2 则将秒杀活动模型注入itemModel
        if(promoModel != null && promoModel.getStatus() != 3) {//Promodel为null 要么不存在秒杀活动 要么3是秒杀活动已结束
            itemModel.setPromoModel(promoModel);
        }
        return itemModel;
    }

    public ItemModel getItemByIdIncache(Integer id) {
        ItemModel itemModel = (ItemModel)redisTemplate.opsForValue().get("item_validate_" + id);
        if(itemModel == null) {
            itemModel = getItemById(id);
            redisTemplate.opsForValue().set("item_validate_"+id, itemModel, 10, TimeUnit.MINUTES);
        }
        return itemModel;
    }

    @Override
    public List<ItemModel> listItem() {
        List<Item> itemList = itemMapper.listItem();
        List<ItemModel> itemModelList = itemList.stream().map(item -> {
            ItemStock itemStock = itemStockMapper.selectByItemId(item.getId());
            ItemModel itemModel = convertModelFromPojo(item, itemStock);
            return itemModel;
        }).collect(Collectors.toList());
        return itemModelList;
    }

    @Override
    @Transactional//配置声明式事务,下单 生成订单 库存减少 销量增加3个事务要保持原子性 而生成流水号是单独的事务,即使生成订单失败,流水号还是会增加
    public boolean decreaseStock(Integer itemId, Integer amount) {
//        int affectedRow = itemStockMapper.decreaseStock(itemId, amount);
        long result = redisTemplate.opsForValue().increment("promo_item_stock_" + itemId, amount * -1);
        if(result>0) {//受影响的行数大于等于0 表示更新库存成功
            return true;//更新库存成功
        } else if(result == 0) {
            //打上库存已售罄的表示
            redisTemplate.opsForValue().set("promo_item_stock_invalid_"+itemId,"true");
            return true;//更新库存成功
        } else {
            //更新库存失败 实际redis中的缓存库存数量不足,下单失败,需要回滚redis中的库存
            increaseStock(itemId, amount);
            return false;
        }
    }

//    @Override
//    public boolean asyncDecreaseStock(Integer itemId, Integer amount) {
//        boolean mqResult = mqProducer.asyncReduceStock(itemId, amount);
//        return mqResult;
//    }

    //其实这里针对销量增加也是可以 通过mq异步 发送消息更新数据库 来降低秒杀时刻数据库的访问压力
    @Override
    public boolean increaseStock(Integer itemId, Integer amount) {
        redisTemplate.opsForValue().increment("promo_item_stock_" + itemId, amount);
        return true;
    }

    @Override
    @Transactional
    public void increaseSales(Integer id, Integer amount) {
        itemMapper.increaseSales(id, amount);
    }

    @Override
    @Transactional
    public String initStockLog(Integer itemId, Integer amount) {
        StockLog stockLog = new StockLog();
        stockLog.setStockLogId(UUID.randomUUID().toString().replace("-",""));
        stockLog.setItemId(itemId);
        stockLog.setAmount(amount);
        stockLog.setStatus(1);//1表示初始状态
        stockLogMapper.insertSelective(stockLog);
        return stockLog.getStockLogId();
    }

    private ItemModel convertModelFromPojo(Item item, ItemStock  itemStock) {
        ItemModel itemModel = new ItemModel();
        BeanUtils.copyProperties(item, itemModel);
        itemModel.setPrice(new BigDecimal(item.getPrice()));
        itemModel.setStock(itemStock.getStock());
        return itemModel;
    }
}
