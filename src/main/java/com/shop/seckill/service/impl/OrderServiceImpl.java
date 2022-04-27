package com.shop.seckill.service.impl;

import com.shop.seckill.dao.OrderMapper;
import com.shop.seckill.dao.SequenceMapper;
import com.shop.seckill.dao.StockLogMapper;
import com.shop.seckill.error.BusinessException;
import com.shop.seckill.error.EmBusinessError;
import com.shop.seckill.pojo.Order;
import com.shop.seckill.pojo.Sequence;
import com.shop.seckill.pojo.StockLog;
import com.shop.seckill.service.ItemService;
import com.shop.seckill.service.OrderService;
import com.shop.seckill.service.UserService;
import com.shop.seckill.service.model.ItemModel;
import com.shop.seckill.service.model.OrderModel;
import com.shop.seckill.service.model.PromoModel;
import com.shop.seckill.service.model.UserModel;
import lombok.SneakyThrows;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private ItemService itemService;

    @Autowired
    private SequenceMapper sequenceMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private StockLogMapper stockLogMapper;

    @Override
    @Transactional//配置声明式事务,下单 生成订单 库存减少 销量增加3个事务要保持原子性 而生成流水号是单独的事务,即使生成订单失败,流水号还是会增加
    public OrderModel createOrder(Integer userId, Integer itemId, Integer promoId, Integer amount, String stockLogId) throws BusinessException {
        //1.校验下单状态,下单的商品是否存在，用户是否合法，购买数量是否正确
//        ItemModel itemModel = itemService.getItemById(itemId);
        //秒杀时刻对同一物品下单是高频操作 可以将itemModel放入redis缓存中
        //这里ItemController实际也缓存了商品详情,但是尽量避免在下单的时候ItemModel用之前获取商品详情中的itemModel,应该单独设置一个商品的itemModel
        //因为下单时的商品的某些状态可能和一开始获取商品详情页的状态信息不一致
        ItemModel itemModel = itemService.getItemByIdIncache(itemId);
        if(itemModel == null) {
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"商品不存在");
        }
        //这个地方 本身Controller的登陆就已经做过判定了,能登录用户肯定是存在的 有点没必要
        //在生成秒杀令牌时已经完成了校验 这里注掉 我并没有校验这个 一般都有专门的安全框架校验管理
//        UserModel userModel = userService.getUserById(userId);
//        if(userModel == null) {
//            throw new BusinessException(EmBusinessError.USER_NOT_EXIST);
//        }
        if(amount <=0 || amount>99) {//校验数量信息
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"数量信息不正确");
        }
        PromoModel promoModel = itemModel.getPromoModel();
        //这里itemModel.getPromoModel可能是为null的,因为可能秒杀活动结束,用户仍然点击下单
        //校验秒杀活动是否正在进行中  这个有可能当前时间超过了秒杀结束时间,用户点击了下单,但是此时的promoModel是从新从数据库取出的,它的状态是3,秒杀已结束 导致聚合时itemModel的pormoModel为null
        //如果itemVo里本身promoId就是null,那本身就不存在秒杀活动,不能抛出这个异常,这个异常是当itemVo本身有promoId,就是秒杀进行中,但是秒杀活动已经结束了的时候,点击下单此时promoId不为null,但itemModel里重新聚合的pormoModel是为null的,因为秒杀活动结束
        //其实这个后面前端也加上了对itemVo的秒杀结束时间和当前时间的校验,超过结束时间 商品自动变为正常价 并在前端弹窗给用户秒杀活动已结束,商品已恢复正常价,会将itemVo对应的.promoId设置为null
        if(promoModel == null && promoId != null) {
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"秒杀活动已结束,请刷新页面以正常价购买");
        }
        //校验秒杀活动信息是否正确 在生成秒杀令牌时已经完成了校验 这里注掉
//        if(promoId != null) {
//            //（1）校验对应活动是否存在这个适用商品 有可能一件商品有多个秒杀活动,但实际转换成itemVo里的promoId对应的秒杀活动已经失效,当前itemModel里的PromoModel是另一个活动
//            if(promoId != promoModel.getId()) {
//                throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"当前的秒杀活动已失效,请刷新页面");
//            }
//        }

        //2.落单减库存 支付减库存 这里是只考虑落单减库存
        //实际真实的的业务是支付才会减库存,因为可能会有用户一次同一个物品落很多次单,但是不支付 这个时候如果库存减了,而这个用户最后全部不支付,其它用户就无法下单
        //这里为了简单,只考虑了落单减库存
        //这里只是根据redis中的库存去判断库存是否充足,减少redis中的库存,
        //真正的mysql中的库存较少 放到了整个事务提交之后,根据rocktmq发送异步消息 扣减mysql中的库存
        boolean result = itemService.decreaseStock(itemId, amount);
        if(result == false) {//减库存失败 说明库存不够购买数量
            throw new BusinessException(EmBusinessError.STOCK_NOT_ENOUGH);
        }
        //3.商品销量增加 更新销量
        itemService.increaseSales(itemId, amount);
        //4.生成交易流水号 订单入库,订单号
        OrderModel orderModel = new OrderModel();
        orderModel.setId(generateOrderNo());
        orderModel.setUserId(userId);
        orderModel.setItemId(itemId);
        if(promoId != null) {//秒杀活动进行中 订单价格为秒杀价 这里必须判断前端传来的promoId 不能判断promoModel,因为itemModel是在resdis中缓存的,可能秒杀活动已经结束
            orderModel.setItemPrice(promoModel.getPromoItemPrice());
            orderModel.setPromoId(promoId);
        } else {//否则价格为正常价
            orderModel.setItemPrice(itemModel.getPrice());
        }
        orderModel.setAmount(amount);
        orderModel.setOrderPrice(orderModel.getItemPrice().multiply(new BigDecimal(amount)));
        Order order = convertFromOrderModel(orderModel);
        orderMapper.insertSelective(order);
        //设置库存流水状态为成功
        //这里设置的行锁就只有stockLogId这一条 所以单独的行锁对数据库本身的压力比较小,所以不会影响性能
        StockLog stockLog = stockLogMapper.selectByPrimaryKey(stockLogId);
        if(stockLog == null) {//这个基本不会为null
            throw new BusinessException(EmBusinessError.UNKNOWN_ERROR);
        }
        //设置库存流水状态为 下单扣减库存成功
        stockLog.setStatus(2);
        stockLogMapper.updateByPrimaryKeySelective(stockLog);
        //异步更新库存
        //有可能库存扣减、销量增加都成功,但是用户在支付时遇到了问题,下订单失败,
        //因为此前的@Transactional声明式事务,可以回滚库存、销量,在回滚库存时只会回滚@Transactional相关联的sql语句
        //此前已经将真正的库存扣减以rocketmq异步发送了出去 以及redis总的缓存库存这是无法回滚的,也就是库存莫名地比实际少了,虽然不会多卖,但是数量和实际不符
        //因此要将真正的sql库存减少消息发送 redis缓存减少的相关逻辑放在最后下订单支付成功后再执行.
        //这样才能保障整个库存事务的一致性
        //但是@Transactional注解只有等到整个方法返回时才会commit,即便异步消息发送成功,但是@Transactional commit时也有可能因为网络、磁盘满问题提交失败
        //导致相关的增加订单 销量增加和异步的库存扣减,库存被白白扣掉了 状态不一致
        //可以通过@Transactional 提供的TransactionSynchronizationManager 保障在大的事务提交后再发送异步更新 数据库的库存
        //后面在生产者MqProducer引入了TransactionMQProducer下面这个做法就被遗弃了
//        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
//            @Override
//            //这个方法会在最近的@Transactional成功commit之后才会执行,就是当前的大事务 createOrder
//            public void afterCommit() {
//                boolean mqResult = itemService.asyncDecreaseStock(itemId, amount);
//                //这个解决方案也是存在问题的 一旦消息发送失败 就没有机会扣减库存了 这条消息就永远发送失败了,这条消息必须是发送成功的 后来被遗弃
//                //这个失败的抛出异常先注掉
////                if(!mqResult) {
////                    itemService.increaseStock(itemId, amount);
////                    throw new BusinessException(EmBusinessError.MQ_SEND_FAIL);
////                }
//                super.afterCommit();
//            }
//        });
        //如果这里发生了断链 即像下面这样长时间没有返回就需要 MqProducer里的checkLocalTransaction回调 来检验StockLog的status来返回 commit来改变prepare消息的状态 使得异步消息被消费者接受来更新数据库库存
//        try {
//            Thread.sleep(30000);
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }
        return orderModel;
    }

    //@Transactional的方法不能是private和protected修饰
    @Transactional(propagation = Propagation.REQUIRES_NEW)//而生成流水号是单独的事务,即使生成订单失败,流水号还是会增加
    public String generateOrderNo() {
        //订单号有16位
        StringBuilder stringBuilder = new StringBuilder();
        //前8位为时间信息，年月日 DateTimeFormatter.ISO_DATE指定格式yyyyMMdd
        LocalDateTime now = LocalDateTime.now();
        String nowDate = now.format(DateTimeFormatter.ISO_DATE).replace("-", "");
        stringBuilder.append(nowDate);

        //中间6位为自增序列
        //获取当前sequence
        int sequence = 0;
        Sequence  currentSequence= sequenceMapper.selectByPrimaryKey("order_info");
        sequence = currentSequence.getCurrentValue();//获取当前的流水号
        //更新db中流水号的currentValue为currentValue+step
        //这个中间的自增序列 实际sequence_info表中需要设置 初始值和最大值,当currentValue+step超过最大值时,要将currentValue初始化为初始值,才能保证流水号不会溢出
        currentSequence.setCurrentValue(currentSequence.getCurrentValue()+currentSequence.getStep());
        sequenceMapper.updateByPrimaryKeySelective(currentSequence);
        String sequenceStr = String.valueOf(sequence);
        for(int i=0;i<6-sequenceStr.length();i++) {//不足6位前面补0
            stringBuilder.append(0);
        }
        stringBuilder.append(sequenceStr);
        //最后2位为分库分表位,暂时写死成00
        //userId%100 保证订单能被水平拆分到100个库的100个表中 减轻数据库查询和追加的落单压力 只要用户id是不变的 所产生的订单就能落到固定的库固定的表上面
        stringBuilder.append("00");
        return stringBuilder.toString();
    }

    private Order convertFromOrderModel(OrderModel orderModel) {
        if(orderModel == null) {
            return null;
        }
        Order order = new Order();
        BeanUtils.copyProperties(orderModel, order);
        order.setItemPrice(orderModel.getItemPrice().doubleValue());
        order.setOrderPrice(orderModel.getOrderPrice().doubleValue());
        return order;
    }
}
