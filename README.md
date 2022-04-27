# seckill
商城秒杀系统 springboot+redis+guava cache+rocketmq+transactionMQ+ThreadPool+Google RateLimiter
技术栈包括 springboot+redis+guava cache+rocketmq+transactionMQ+ThreadPool+Google RateLimiter
实现的模块包括 用户登录、注册模块，商品模块开发包括创建商品、商品列表、商品详情，交易模块包括秒杀计时、交易下单，
生成订单，生成库存流水号。并针对商品详情页构建二级缓存，将guava cache作为本地缓存充当一级缓存，redis作为二级缓存。
针对交易模块进行优化，将秒杀商品及其对应库存容量缓存到redis中，降低mysql的访问压力，并采用rocketmq发送异步prepare消息
延缓修改数据库中库存，削峰负载均衡降低服务器访问数据库的压力，并针对下单交易过程中可能出现的服务器异常问题，将异步发送消息
同步数据库库存最后处理，将整个下单流程用rocketmq TransactionMQProducer事务型消息管理，当出现下单流程中断链、长期无响应时，
根据下单过程中生成的库存流水号的状态 在TransactionMQProducer的回调checkLocalTransaction 返回正确的状态以便决策是否发送异步
消息给消费者。并很对库存售罄的情况在redis中加入售罄标志，避免大量用户频繁访问接口，针对流量削峰生成每个秒杀商品对应的秒杀令牌，
根据队列泄洪原理采用线程池设置定长线程依靠排队去限制下单的并发流量大小。对于防刷限流，采用google的RateLimiter的令牌桶算法控制访问接口的次数。
