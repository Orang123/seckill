package com.shop.seckill.controller;

import com.google.common.util.concurrent.RateLimiter;
import com.shop.seckill.error.BusinessException;
import com.shop.seckill.error.EmBusinessError;
import com.shop.seckill.mq.MqProducer;
import com.shop.seckill.response.CommonReturnType;
import com.shop.seckill.service.ItemService;
import com.shop.seckill.service.OrderService;
import com.shop.seckill.service.PromoService;
import com.shop.seckill.service.model.UserModel;
import com.shop.seckill.util.CodeUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;

@RestController
@RequestMapping("/order")
@CrossOrigin(allowCredentials="true", allowedHeaders = "*")
public class OrderController extends BaseController{

    @Autowired
    private OrderService orderService;

    @Autowired
    private HttpServletRequest request;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private MqProducer mqProducer;

    @Autowired
    private ItemService itemService;

    @Autowired
    private PromoService promoService;

    private ExecutorService executorService;

    private RateLimiter orderCreateRateLimiter;

    //当前采用本地模式:将队列维护在本地内存中,还有分布式:将队列设置到外部redis内
    //实际本地模式的性能要好一些
    //tps:衡量对数据库产生解操作 transation事务操作一个容量指标
    //qps:查询的每秒数量指标
    //限流方案:1.令牌桶算法 2.漏桶算法 这里是采用令牌桶算法
    @PostConstruct
    public void init() {
        //创建一个定长线程池，可控制线程最大并发数，超出的线程会在队列中等待。核心线程数=最大线程数=20
        //LinkedBlockingQueue 队列元素个数没有限制
        executorService = Executors.newFixedThreadPool(20);
        //tps一秒中允许通过300个
        orderCreateRateLimiter = RateLimiter.create(300);
    }

    //下单 promoId required = false 可以不传参 值为null 普通商品可以没有promoId和promoToken
    /*
    * required=true表示前端必须传参数。
      required=false表示前端不传参数的时候，会将参数置为null。因此假如参数是int这种不能赋值为null的类型，就可能会报错。
      使用了defaultValue，required只能为false，前端不传参数时会将参数置为defaultValue。
    * */
    @RequestMapping(path = "/create",method = RequestMethod.POST,consumes={CONTENT_TYPE_FORMED})
    public CommonReturnType createOrder(Integer itemId, @RequestParam(required = false) Integer promoId,
                                        Integer amount, @RequestParam(required = false) String promoToken) throws BusinessException {
        //在访问create controller时采用计数器 一旦超过计数器最大上限 就限流 拒绝访问
        //google guava限流 超过300后 controller请求就会等待 线程会sleep
        if(!orderCreateRateLimiter.tryAcquire()) {
            throw new BusinessException(EmBusinessError.RATELIMIT);
        }
        //弃用基于cookie传输的session 无法应用于分布式 如果有多台服务器 nginx转发时 session无法存于多台服务器
//        Boolean is_login = (Boolean) request.getSession().getAttribute("IS_LOGIN");
//        if(is_login == null || is_login.booleanValue() == false) {
//            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN,"用户还未登陆，不能下单");
//        }
//        UserModel login_user = (UserModel)request.getSession().getAttribute("LOGIN_USER");
        String token = request.getParameterMap().get("token")[0];
        if(StringUtils.isEmpty(token)) {
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN,"用户还未登陆，不能下单");
        }
        UserModel login_user = (UserModel)redisTemplate.opsForValue().get(token);
        if(login_user == null) {
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN,"用户的登陆凭证已过期，不能下单");
        }
        //校验秒杀令牌是否正确 不正确就可能是通过脚本接口 刷新而来的 就不能秒杀
        if(promoId != null) {
            String inRedisPromoToken = (String)redisTemplate.opsForValue().get("promo_token_"+promoId+"_userId_"+login_user.getId()+"_itemId_"+itemId);
            if(inRedisPromoToken == null) {
                throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, "秒杀令牌校验失败");
            }
            if(!StringUtils.equals(inRedisPromoToken, promoToken)) {
                throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, "秒杀令牌校验失败");
            }
        }

//        OrderModel order = orderService.createOrder(login_user.getId(), itemId, promoId, amount);
        //排队有些时候比并发更高效 多线程并发涉及锁的等待 行锁,锁的竞争消耗cpu调度 线程切换开销很耗性能
        //依靠排队去限制并发流量,依靠排队和下游拥塞窗口程度调整队列释放流量大小 eg:支付宝银行网关队列举例 seiq
        //同步调用线程池的submit方法 同一时间只有20个订单会被生成,其它请求会等待 这样的队列等待 比大批量高并发请求性能要好
        //拥塞窗口为20的等待队列，用来队列化泄洪
        //因为大量高并发请求sql会涉及到数据库行锁的设置 锁的创建和释放 开销很大 好耗费资源
        Future<Object> future = executorService.submit(new Callable<Object>() {//submit方法是 ThreadPoolTaskExecutor中的
            @Override
            public Object call() throws Exception {
                //加入库存流水init状态
                String stockLogId = itemService.initStockLog(itemId, amount);
                //再去完成对应的下单事务型消息机制
                //需要保证在redis库存扣减、商品销量增加、订单增加这三个事务都成功的情况下 最后再执行异步发送消息 扣减数据库中的库存 采用了rokectmq中的事务型异步扣减库存 方法 直接将createOrder操作放在prepare消息后的executeLocalTransaction
                if(mqProducer.transactionAsyncReduceStock(login_user.getId(),itemId,promoId,amount,stockLogId) == false) {
                    throw new BusinessException(EmBusinessError.UNKNOWN_ERROR, "下单失败");
                }
                return null;
            }

        });
        try {
            //future.get()方法能获取到当前线程的执行结果，但是会阻塞当前线程，即当前线程执行结束获取到结果后才会继续执行下一个线程
            future.get();
        } catch (InterruptedException e) {
            throw new BusinessException(EmBusinessError.UNKNOWN_ERROR);
        } catch (ExecutionException e) {
            throw new BusinessException(EmBusinessError.UNKNOWN_ERROR);
        }
        return CommonReturnType.create(null);
    }

    //生成秒杀令牌 promoId必传
    @RequestMapping(path = "/generateToken",method = RequestMethod.POST,consumes={CONTENT_TYPE_FORMED})
    public CommonReturnType generatetoken(Integer itemId,  Integer promoId, String verifyCode) throws BusinessException {
        //根据token获取用户信息
        String token = request.getParameterMap().get("token")[0];
        if(StringUtils.isEmpty(token)) {
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN,"用户还未登陆，不能下单");
        }
        //获取用户的登陆信息
        UserModel login_user = (UserModel)redisTemplate.opsForValue().get(token);
        if(login_user == null) {
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN,"用户的登陆凭证已过期，不能下单");
        }
        //通过verifycode验证验证码的有效性
        String redisVerifyCode = (String) redisTemplate.opsForValue().get("verify_code_"+login_user.getId());
        if(StringUtils.isEmpty(redisVerifyCode)){
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"请求非法");
        }
        if(!redisVerifyCode.equalsIgnoreCase(verifyCode)){
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"请求非法，验证码错误");
        }
        //获取秒杀令牌
        String promoToken = promoService.generateSecondKillToken(promoId, itemId, login_user.getId());
        if(promoToken == null) {
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"生成秒杀令牌失败");
        }
        //返回对应结果
        return CommonReturnType.create(promoToken);
    }

    //生成验证码
    @RequestMapping(value = "/generateverifycode",method = {RequestMethod.GET,RequestMethod.POST})
    public void generateverifycode(HttpServletResponse response) throws BusinessException, IOException {
        String token = request.getParameterMap().get("token")[0];
        if(org.springframework.util.StringUtils.isEmpty(token)){
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN,"用户还未登陆，不能生成验证码");
        }
        UserModel login_user = (UserModel) redisTemplate.opsForValue().get(token);
        //这里有点小bug,就是实际用户没登陆 因为img src是异步请求的,但不是ajax那种 可以判断返回值 导致点击下单前台看不到验证码 也没有信息提示,
        //所以这里不能抛出异常,把用户未登录的校验放到生成秒杀令牌request中
//        if(login_user == null){
//            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN,"用户的登陆凭证已过期，不能下单");
//        }
        Map<String,Object> map = CodeUtil.generateCodeAndPic();
        int userId = 0;//这里userId默认设置为0,否则用户未登录 login_user.getId()这里会报错,导致服务端程序异常,最终用户未登录的校验需要暂时放到秒杀令牌生成流程中
        if(login_user != null) {
            userId = login_user.getId();
        }
        System.out.println(map.get("code"));
        //将验证码放入redis中存活周期 10分钟
        redisTemplate.opsForValue().set("verify_code_"+userId, map.get("code"), 10, TimeUnit.MINUTES);
        ImageIO.write((RenderedImage) map.get("codePic"), "jpeg", response.getOutputStream());

    }

}
