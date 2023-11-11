package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    //lua脚本初始化
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //创建线程池
    private static final ExecutorService SECKILL_VOUCHER_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    @PostConstruct
    private void init() {
        SECKILL_VOUCHER_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable {
        String redisStreamName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    //获取redis消息队列的订单信息
                    // xreadgroup group g1 c1 count 1 block 2000 stream stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(redisStreamName, ReadOffset.lastConsumed())
                    );
                    //判断消息获取是否成功
                    //否，进入下一次循环
                    if (list==null || list.isEmpty()) {
                        //如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    //是：说明有消息，处理
                    //解析订单中的消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                    //ack确认 sack stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(redisStreamName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常:{}", e.getMessage());
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    //获取redis消息队列的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(redisStreamName, ReadOffset.from("0"))
                    );
                    //判断消息获取是否成功
                    //否，进入下一次循环
                    if (list==null || list.isEmpty()) {
                        //如果获取失败，说明pendingList中没有消息，结束循环
                        break;
                    }
                    //是：说明有消息，处理
                    //解析订单中的消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                    //ack确认 sack stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(redisStreamName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常:{}", e.getMessage());
                }
            }
        }
    }

    /*private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    //获取阻塞队列队列的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常:{}", e.getMessage());
                }
            }
        }
    }*/
    private IVoucherOrderService proxy;
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户id
        Long userId = voucherOrder.getUserId();
        //获取锁对象
        RLock rLock = redissonClient.getLock("lock:order" + userId);
        boolean hasLock = rLock.tryLock();
        //判断是否获得锁
        if (!hasLock) {
            //获取锁失败，返回错误或者重试
            log.error("该优惠券只可以购买一次！");
        }
        try {
            //此时是子线程，这个代理对象无法获取，必须在主线程中提前获取，子线程来调用
            //IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            rLock.unlock();
        }
    }

    //创建阻塞队列
    private static final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    @Override
    public Result seckillVoucher(Long voucherId) {
        //用户id
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        long orderId = redisIdWorker.nextId("order");
        //1.执行lua脚本,判断购买资格且有资格将消息发送到redis消息队列
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        //2.判断结果是否是0，0代表有资格
        int r = result.intValue();
        if (r != 0) {
            //2.1 没有资格，返回对应的信息
            return Result.fail(r == 1 ? "库存不足" : "不能重复购买");
        }
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //5.返回订单id
        return Result.ok(orderId);
    }

    /*@Override
        public Result seckillVoucher(Long voucherId) {
        //用户id
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        //2.判断结果是否是0，0代表有资格
        int r = result.intValue();
        if (r != 0) {
            //2.1 没有资格，返回对应的信息
            return Result.fail(r == 1 ? "库存不足" : "不能重复购买");
        }
        //3.为0，下单信息保存到阻塞队列
        long orderId = redisIdWorker.nextId("order");
        //4.保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id，用户id，代金券id
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        orderTasks.add(voucherOrder);
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //5.返回订单id
        return Result.ok(orderId);
    }*/
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！");
        }
        //判断秒杀是够结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }
        //判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足！");
        }
        Long userId = UserHolder.getUser().getId();
        *//*synchronized (userId.toString().intern()) {
            //获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }*//*

        //获取锁对象
        //这里拼接用户id，因为只需要给同一个用户加锁，否则给所有的order都加锁了
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        //redisson锁
        RLock rLock = redissonClient.getLock("lock:order" + userId);
        //boolean hasLock = lock.tryLock(1200);
        boolean hasLock = rLock.tryLock();
        //判断是否获得锁
        if (!hasLock) {
            //获取锁失败，返回错误或者重试
            return Result.fail("该优惠券只可以购买一次！");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            rLock.unlock();
        }
    }*/

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 5.1.查询订单
        int count = query().eq("user_id", userId).eq("voucher_id",
                voucherOrder.getVoucherId()).count();
        // 5.2.判断是否存在 
        if (count > 0) {
            // 用户已经购买过了
            log.error("用户已经购买过一次!");
        }

        //扣减库存
        boolean success = seckillVoucherService.lambdaUpdate()
                .setSql("stock = stock - 1")
                .eq(SeckillVoucher::getVoucherId, voucherOrder.getVoucherId())
                .gt(SeckillVoucher::getStock, 0)
                .update();
        if (!success) {
            log.error("库存不足！");
        }

        save(voucherOrder);
    }
}
