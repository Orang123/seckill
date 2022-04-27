package com.shop.seckill.mq;

import com.alibaba.fastjson.JSON;
import com.shop.seckill.dao.StockLogMapper;
import com.shop.seckill.error.BusinessException;
import com.shop.seckill.pojo.StockLog;
import com.shop.seckill.service.OrderService;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

@Component
public class MqProducer {

    private DefaultMQProducer producer;

    @Value("${mq.nameserver.addr}")
    private String nameAddr;

    @Value("${mq.topicname}")
    private String topicName;

    private TransactionMQProducer transactionMQProducer;

    @Autowired
    private OrderService orderService;

    @Autowired
    private StockLogMapper stockLogMapper;

    @PostConstruct
    public void init() throws MQClientException {
        //做mq producer的初始化
        producer = new DefaultMQProducer("producer_group");
        producer.setNamesrvAddr(nameAddr);
        producer.start();
        transactionMQProducer = new TransactionMQProducer("transation_producer_group");
        transactionMQProducer.setNamesrvAddr(nameAddr);
        transactionMQProducer.start();
        transactionMQProducer.setTransactionListener(new TransactionListener() {
            //消息在prepare状态下即:执行transactionMQProducer.sendMessageInTransaction后 会在客户端执行executeLocalTransaction这个方法
            //LocalTransactionState中定义的   三个消息COMMIT_MESSAGE,ROLLBACK_MESSAGE,UNKNOW;
            //prepare消息的默认状态是UNKNOW
            //COMMIT_MESSAGE 代表将之前的prepare消息转化成COMMIT消息给对应的消费方去消费
            //ROLLBACK_MESSAGE 代表将之前的prepare消息撤回 等于没发送
            //UNKNOW 现在的状态自己也不知道 消息中间件将prepare消息维护在了内存内,后续可以在询问是什么状态
            @Override
            public LocalTransactionState executeLocalTransaction(Message message, Object args) {
                //真正要做的事 创建订单
                Integer userId = (Integer)((Map)args).get("userId");
                Integer itemId = (Integer)((Map)args).get("itemId");
                Integer promoId = (Integer)((Map)args).get("promoId");
                Integer amount = (Integer)((Map)args).get("amount");
                String stockLogId = (String)((Map)args).get("stockLogId");
                try {
                    //这里有个小缺陷 如果createOrder 长时间不返回或者程序死掉了,永远无法触发stockLog.setStatus(3);或者返回COMMIT_MESSAGE,
                    //么库存流水状态永远都是1,checkLocalTransaction中永远返回UNKNOW,redis库存已经减掉了 但是异步消息永远不会被消费者消费,程序大面积假死,商品就卖不出去
                    //需要设置超时释放的机制 超时没有提交就自动回滚redis中库存,把库存加上去释放掉.
                    orderService.createOrder(userId, itemId, promoId, amount, stockLogId);
                    //createOrder 又没有返回ROLLBACK_MESSAGE也没有返回COMMIT_MESSAGE时有多种情况如下:
                    //1.COMMIT_MESSAGE没发送出去,往事务型消息中间件发确认消息的消息丢了,没发送成功 进程就死了,但本身createOrder是成功的
                    //2.ROLLBACK_MESSAGE没发送出去 createOrder本身是失败的
                    //3.createOrder方法没执行完,就产生了回调checkLocalTransaction,拿到的stockLogStatus仍旧是init状态
                } catch (BusinessException e) {
                    e.printStackTrace();
                    //如果创建订单过程出现的业务上BusinessException异常 则设置对应的stockLog为回滚状态
                    //有可能下面的return LocalTransactionState.ROLLBACK_MESSAGE; 没有正常返回,等到回调checkLocalTransaction时 就会根据stockLog.status = 3返回回滚状态
                    StockLog stockLog = stockLogMapper.selectByPrimaryKey(stockLogId);
                    stockLog.setStatus(3);
                    stockLogMapper.updateByPrimaryKeySelective(stockLog);
                    //订单提交失败 之前生产者异步发送的消息就需要撤回 不必再让消费者消费 扣减数据库中的库存
                    return LocalTransactionState.ROLLBACK_MESSAGE;
                }
                //订单创建成功 之前生产者异步发送的prepare消息就会被消费者接收到
                return LocalTransactionState.COMMIT_MESSAGE;
            }

            //针对executeLocalTransaction 返回UNKNOW状态时即不是rollback或commit或长时间没有返回状态发送给消息中间件,这是由于createOrder断链,由于是不明确状态,消息中间件就会定期回调checkLocalTransaction方法
            @Override
            public LocalTransactionState checkLocalTransaction(MessageExt messageExt) {//可以通过在orderServiceimpl里Thread.sleep(30000)来测试
                //根据是否扣减库存成功，来判断要返回COMMIT,ROLLBACK还是继续UNKNOWN
                String jsonString = new String(messageExt.getBody());
                Map<String, Object> map = JSON.parseObject(jsonString, Map.class);
                Integer itemId = (Integer)map.get("itemId");
                Integer amount = (Integer)map.get("amount");
                String stockLogId = (String)map.get("stockLogId");
                StockLog stockLog = stockLogMapper.selectByPrimaryKey(stockLogId);
                //这种情况一般不会发生
                if(stockLog == null) {
                    //返回UNKNOW 告诉消息中间件不知什么情况,下次再来问
                    //一般重试 不是无脑重试询问,会按照对应重试频率逐渐放小地方式去重试
                    //eg:1分钟 3分钟 8分钟这样的间隔去回调checkLocalTransaction 直到7天后 仍然不是非UNKNOW就不去式了
                    return LocalTransactionState.UNKNOW;
                }
                //状态为2 说明createorder正常结束 可以直接发送消息给消费者异步扣减数据库库存
                if(stockLog.getStatus() == 2) {
                    return LocalTransactionState.COMMIT_MESSAGE;
                } else if(stockLog.getStatus() == 1) {//说明createOrder方法没有正常返回,返回UNKNOW下次再来回调checkLocalTransaction重试
                    return LocalTransactionState.UNKNOW;
                }
                //其余情况一律回滚
                return LocalTransactionState.ROLLBACK_MESSAGE;
            }
        });
    }

    //事务型异步库存扣减消息
    public boolean transactionAsyncReduceStock(Integer userId, Integer itemId, Integer promoId, Integer amount, String stockLogId) {
        Map<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("itemId", itemId);
        bodyMap.put("amount", amount);
        bodyMap.put("stockLogId", stockLogId);
        Map<String, Object> argsMap = new HashMap<>();
        argsMap.put("userId", userId);
        argsMap.put("itemId", itemId);
        argsMap.put("promoId", promoId);
        argsMap.put("amount", amount);
        argsMap.put("stockLogId", stockLogId);
        Message message = new Message(topicName,"increase",
                JSON.toJSON(bodyMap).toString().getBytes(Charset.forName("UTF-8")));
        TransactionSendResult sendResult = null;
        try {
            //之前的非事务型发送消息是不管什么情况只要把消息发送出去,消费者得到通知 都会扣减数据库中的库存
//            producer.send(message);
            //现在发送事务型消息 采用两阶段提交 message broker会收到消息,但是消息状态并不是可被消费状态,而是prepare(准备/就绪)状态
            //在prepare状态下对应的操作是不会被执行的,消息并不会被消费者看到
            sendResult = transactionMQProducer.sendMessageInTransaction(message, argsMap);
        } catch (MQClientException e) {
            e.printStackTrace();
            return false;
        }
        if(sendResult.getLocalTransactionState() == LocalTransactionState.ROLLBACK_MESSAGE) {
            return false;
        } else if(sendResult.getLocalTransactionState() == LocalTransactionState.COMMIT_MESSAGE) {//创建订单成功
            return true;
        } else {
            return false;
        }
    }

    //异步库存扣减消息
    //因为要保证异步发送消息扣减数据库库存 是在创建订单事务全部成功的条件下,以及生产者发送消息成功 该方法被遗弃
//    public boolean asyncReduceStock(Integer itemId, Integer amount) {
//        Map<String, Object> bodyMap = new HashMap<>();
//        bodyMap.put("itemId", itemId);
//        bodyMap.put("amount", amount);
//        Message message = new Message(topicName,"increase",
//                JSON.toJSON(bodyMap).toString().getBytes(Charset.forName("UTF-8")));
//        try {
//            producer.send(message);
//        } catch (MQClientException e) {
//            e.printStackTrace();
//            return false;
//        } catch (RemotingException e) {
//            e.printStackTrace();
//            return false;
//        } catch (MQBrokerException e) {
//            e.printStackTrace();
//            return false;
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//            return false;
//        }
//        return true;
//    }
}
