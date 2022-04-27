package com.shop.seckill.service.impl;

import com.shop.seckill.dao.PromoMapper;
import com.shop.seckill.error.BusinessException;
import com.shop.seckill.error.EmBusinessError;
import com.shop.seckill.pojo.Promo;
import com.shop.seckill.service.ItemService;
import com.shop.seckill.service.PromoService;
import com.shop.seckill.service.UserService;
import com.shop.seckill.service.model.ItemModel;
import com.shop.seckill.service.model.PromoModel;
import com.shop.seckill.service.model.UserModel;
import org.joda.time.DateTime;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class PromoServiceImpl implements PromoService {

    @Autowired
    private PromoMapper promoMapper;

    @Autowired
    private ItemService itemService;

    @Autowired
    private RedisTemplate redisTemplate;

    @Override
    public PromoModel getPromoByItemId(Integer itemId) {
        Promo promo = promoMapper.selectByItemId(itemId);
        PromoModel promoModel = convertFromPojo(promo);
        if(promoModel == null) {
            return null;
        }
        //设置当前时间promodel的秒杀状态
        if(promoModel.getStartDate().isAfterNow()) {//秒杀活动开始时间在当前时间之后 秒杀活动开始
            promoModel.setStatus(1);
        } else if(promoModel.getEndDate().isBeforeNow()) {//秒杀活动结束时间在当前时间之前 秒杀活动结束
            promoModel.setStatus(3);
        } else {//其余情况秒杀活动进行中
            promoModel.setStatus(2);
        }
        return promoModel;
    }

    //这个发布活动 实际没有做可视化的UI插入 就是在数据库手动插入的,然后再Controller层调用这个方法即可
    //因为发布活动之后活动还未开始时有可能商品会通过普通价格售卖,导致存入redis中的库存和实际的库存状态不一致
    //但这里不考虑这个问题,在发布活动之后,活动未开始会下架商品就是不能下单,这样就能保证redis中的库存和实际数据库中的库存状态是一致的
    @Override
    public void publishPromo(Integer promoId) {
        //通过活动id获取活动
        Promo promo = promoMapper.selectByPrimaryKey(promoId);
        if(promo.getItemId() == null || promo.getItemId() == 0) {//活动没有对应秒杀商品 结束不发布
            return;
        }
        ItemModel itemModel = itemService.getItemById(promo.getItemId());
        //将库存同步到redis中 这里不设置失效时间要可持久化到redis中
        redisTemplate.opsForValue().set("promo_item_stock_"+itemModel.getId(), itemModel.getStock());
        //将大闸的限制数字设到redis内 秒杀大闸的限制数量设为实际库存的5倍,实际具体谁能抢购到商品取决于redis库存扣减和stocklog状态的情况
        //之所以设置为5倍 让秒杀令牌的数量远远大于库存数量,是因为部分用户选定商品 并不会下单,所以秒杀令牌数量要多余库存数量
        redisTemplate.opsForValue().set("promo_door_count_"+promoId, itemModel.getStock()*5);
    }

    @Override
    public String generateSecondKillToken(Integer promoId, Integer itemId, Integer userId) {
        //这里如果本身redis中的库存已经售罄,还有很多下单请求 就会无意义的init很多StockLog库存流水,状态永远为1,
        //为了降低对数据库的访问压力,可以在redis中针对每个商品设置库存售罄的标识,一旦发现售罄标识就不再生成库存流水
        //判断是否库存已售罄，若对应的售罄key存在，则直接返回生成秒杀令牌失败
        if(redisTemplate.hasKey("promo_item_stock_invalid_"+itemId)) {
            return null;
        }
        Promo promo = promoMapper.selectByPrimaryKey(promoId);
        PromoModel promoModel = convertFromPojo(promo);
        if(promoModel == null) {
            return null;
        }
        if(promoModel.getStartDate().isAfterNow()) {//秒杀活动开始时间在当前时间之后 秒杀活动开始
            promoModel.setStatus(1);
        } else if(promoModel.getEndDate().isBeforeNow()) {//秒杀活动结束时间在当前时间之前 秒杀活动结束
            promoModel.setStatus(3);
        } else {//其余情况秒杀活动进行中
            promoModel.setStatus(2);
        }
        //只要不是秒杀进行中,就不会生成秒杀令牌
        if(promoModel.getStatus() != 2) {
            return null;
        }
        //这里秒杀令牌的获取 主要是为了防止脚本 生成大量post请求,所以视频中才加上了itemId和userId的校验
        //但实际关于下单 本身就是要求用户登录的,关于用户信息的校验 有专门的security活shiro框架去做 没必要在这里校验
        //判断item信息是否存在 不存在就不生成秒杀令牌
        ItemModel itemModel = itemService.getItemByIdIncache(itemId);
        if(itemModel == null) {
            return null;
        }
        //获取秒杀大闸的数量
        long result = redisTemplate.opsForValue().increment("promo_door_count_" + promoId, -1);
        if(result < 0) {//小于0 没有秒杀大闸,秒杀令牌就失效
            return null;
        }
        //生成token
        String token = UUID.randomUUID().toString().replace("-", "");
        //将秒杀token存入redis,过期时间5分钟
        redisTemplate.opsForValue().set("promo_token_"+promoId+"_userId_"+userId+"_itemId_"+itemId, token, 5, TimeUnit.MINUTES);
        return token;
    }

    private PromoModel convertFromPojo(Promo promo) {
        if(promo == null) {
            return null;
        }
        PromoModel promoModel = new PromoModel();
        BeanUtils.copyProperties(promo, promoModel);
        promoModel.setPromoItemPrice(new BigDecimal(promo.getPromoItemPrice()));//Double转成BigDecimal
        promoModel.setStartDate(new DateTime(promo.getStartDate()));//Date转成DateTime
        promoModel.setEndDate(new DateTime(promo.getEndDate()));
        return promoModel;
    }
}
