package com.shop.seckill.controller;

import com.shop.seckill.controller.viewobject.ItemVo;
import com.shop.seckill.error.BusinessException;
import com.shop.seckill.error.EmBusinessError;
import com.shop.seckill.response.CommonReturnType;
import com.shop.seckill.service.CacheService;
import com.shop.seckill.service.ItemService;
import com.shop.seckill.service.PromoService;
import com.shop.seckill.service.model.ItemModel;
import com.shop.seckill.service.model.PromoModel;
import org.joda.time.format.DateTimeFormat;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/item")
@CrossOrigin(allowCredentials="true", allowedHeaders = "*")
public class ItemController extends BaseController {

    @Autowired
    private ItemService itemService;

    @Autowired
    private HttpServletRequest request;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private CacheService cacheService;

    @Autowired
    private PromoService promoService;

    @RequestMapping(path = "/create",method = RequestMethod.POST,consumes={CONTENT_TYPE_FORMED})
    //这里的形参可以只写个itemModel,springmvc能自动识别,一开始以为model模型能干啥别的 就跟着视频里的写了...结果发现也只是转换一下
    public CommonReturnType createItem(String title, BigDecimal price, Integer stock, String description, String imgUrl) throws BusinessException {
        Boolean is_login = (Boolean) request.getSession().getAttribute("IS_LOGIN");
        if(is_login == null || is_login.booleanValue() == false) {
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN,"用户还未登陆，不能创建商品");
        }
        ItemModel itemModel = new ItemModel();
        itemModel.setTitle(title);
        itemModel.setPrice(price);
        itemModel.setStock(stock);
        itemModel.setDescription(description);
        itemModel.setImgUrl(imgUrl);
        itemService.createItem(itemModel);
        ItemVo itemVo = convertVoFromModel(itemModel);
        return CommonReturnType.create(itemVo);
    }

    private ItemVo convertVoFromModel(ItemModel itemModel) {
        if(itemModel == null) {
            return null;
        }
        ItemVo itemVo = new ItemVo();
        BeanUtils.copyProperties(itemModel,itemVo);
        PromoModel promoModel = itemModel.getPromoModel();
        if(promoModel != null) {//有正在进行或即将进行的秒杀活动
            itemVo.setPromoId(promoModel.getId());
            itemVo.setPromoPrice(promoModel.getPromoItemPrice());
            itemVo.setPromoStatus(promoModel.getStatus());//1(秒杀活动尚未开始) 2(秒杀活动进行中)
            //这里用String是因为展示给前端的json无法解析 java joda-time的DateTime模型
            itemVo.setStartDate(promoModel.getStartDate().toString(DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss")));
            itemVo.setEndDate(promoModel.getEndDate().toString(DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss")));
        } else {//要么不存在秒杀活动 要么秒杀活动已结束 这种情况itemVo.promoId是为null的
            itemVo.setPromoStatus(0);//itemVo的秒杀结束状态是0 不是promoModel中的结束状态3
        }
        return itemVo;
    }

    @RequestMapping(path = "/get",method = RequestMethod.GET)
    public CommonReturnType getItem(Integer id) {
        ItemModel itemModel = null;
        //先取一级缓存 本地缓存google guava cache的效率要比redis还要高 直接存放在服务器内存中的
        //这个本地缓存会影响下单之后当前商品详情里的库存销量显示,实际数据库已经更新了,但是本地缓存的商品信息还是之前的 单步影响实际的操作
        itemModel = (ItemModel)cacheService.getFromCommonCache("item_"+id);
        if(itemModel == null) {
            //一级缓存本地缓存未命中不存在 再在二级缓存redis中去取
            itemModel = (ItemModel)redisTemplate.opsForValue().get("item_" + id);
            //如果二级缓存中未存放itemModel,则在数据库mysql中查找,再将其放入redis二级缓存 失效时间10分钟
            if(itemModel == null) {
                itemModel = itemService.getItemById(id);
                redisTemplate.opsForValue().set("item_"+id, itemModel, 10, TimeUnit.MINUTES);
            } else {
                PromoModel promoModel = itemModel.getPromoModel();
                if(promoModel != null) {//之前存入redis中的商品有对应的秒杀活动
                    //存入redis中的itemModel对应的秒杀活动已经结束 需要更新redis中的key-value
                    //秒杀结束时间在当前时间之前 秒杀活动失效 避免前端取到redis中失效的秒杀活动 超过秒杀时间还能以秒杀价购买
                    //实际这个做法只要前端判断itemVo秒杀结束时间是否超过当前时间也是可以的,这里为了安全前后端都做校验
                    //这里并不对本地缓存guava cache的数据变更做处理,因为本身缓存的时间就很短,失效后又会从redis中去取,再加上前端也会对比结束时间和当前时间
                    //实际本地缓存的失效的时间应该设置地比秒杀活动的持续时间要小,这样当出现提示秒杀活动结束,刷新商品详情时 就不会取到之前放入本地缓存中的商品(还在秒杀状态),这里本地缓存的失效时间默认是1min
                    if(promoModel.getEndDate().isBeforeNow()) {
                        itemModel.setPromoModel(null);
                        redisTemplate.opsForValue().set("item_"+id, itemModel, 10, TimeUnit.MINUTES);
                    }
                }
            }
            //将itemModel放入一级缓存 本地缓存 google guava cache
            cacheService.setCommonCache("item_"+id, itemModel);
        }
        ItemVo itemVo = convertVoFromModel(itemModel);
        return CommonReturnType.create(itemVo);
    }

    @RequestMapping(path = "/list",method = RequestMethod.GET)
    public CommonReturnType getItem() {
        List<ItemModel> itemModelList = itemService.listItem();
        //使用stream apiJ将list内的itemModel转化为itemVo;
        List<ItemVo> itemVoList = itemModelList.stream().map(itemModel -> {
            ItemVo itemVo = convertVoFromModel(itemModel);
            return itemVo;
        }).collect(Collectors.toList());
        return CommonReturnType.create(itemVoList);
    }

    @RequestMapping(path = "/publishPromo/{promoId}",method = RequestMethod.GET)
    public CommonReturnType publishPromo(@PathVariable("promoId") Integer promoId) {
        promoService.publishPromo(promoId);
        return CommonReturnType.create(null);
    }

}
