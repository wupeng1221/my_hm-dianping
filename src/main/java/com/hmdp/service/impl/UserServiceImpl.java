package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate = new StringRedisTemplate();
    @Resource
    private UserMapper userMapper;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("请输入合法的手机号");
        }
        //2.生成验证码,存入redis
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.debug("登录验证码为: {}", code);
        return Result.ok(code);
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("请输入合法的手机号");
        }
        //2.验证码校验
        //2.1redis读取验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("请输入正确的验证码");
        }
        //校验通过，检查用户是否已经存在
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        //不存在，说明是第一次登录直接注册一个，加入数据库
        if (user == null) {
            user = genUserWithPhone(phone);
            userMapper.insert(user);
        }
        //将用户的登录信息存入redis，其中key设置为token(UUID)
        String token = UUID.fastUUID().toString();
        UserDTO userDTO = new UserDTO();
        BeanUtil.copyProperties(user, userDTO);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,
                new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);
        return Result.ok(token);
    }

    @Resource
    private HttpServletRequest request;

    @Override
    public Result logout() {
        //获取token
        String token = request.getHeader("authorization");
        stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        UserHolder.removeUser();
        return Result.ok();
    }

    private HashMap<String, String> getUserSignKey() {
        HashMap<String, String> hashMap = new HashMap<>();
        // key : [prefix] + userId + [suffix(date年月日)]
        Long userId = UserHolder.getUser().getId();
        LocalDateTime localDateTime = LocalDateTime.now();
        String keySuffix = localDateTime.format(DateTimeFormatter.ofPattern(":yyMMdd"));
        hashMap.put("userSignKey", RedisConstants.USER_SIGN_KEY + userId + keySuffix);
        // hashMAP保存第二个元素是当前日期是本月的第几天
        hashMap.put("dayOfMonth", String.valueOf(localDateTime.getDayOfMonth()));
        return hashMap;
    }

    @Override
    public Result sign() {
        HashMap<String, String> map = getUserSignKey();
        String userSignKey = map.get("userSignKey");
        int dayOfMonth = Integer.parseInt(map.get("dayOfMonth"));
        // bitMap记录数据，索引从0开始，offset在原本基础上减一
        stringRedisTemplate.opsForValue().setBit(userSignKey, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 统计本月的累计签到天数
        HashMap<String, String> map = getUserSignKey();
        String userSignKey = map.get("userSignKey");
        int dayOfMonth = Integer.parseInt(map.get("dayOfMonth"));
        // 选择一个需要截取的无符号位数,BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)),valueAt的参数是开始位置
        // 函数返回值是List集合，对应每个subcommand的返回值，我们只添加了一个操作，因此List中只有一个元素
        List<Long> bitField = stringRedisTemplate.opsForValue().bitField(userSignKey,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (bitField == null || bitField.isEmpty()) {
            return Result.ok(0);
        }
        // 如果这个截取的数字直接是0，那说明没有签到，无需后续的位运算
        // 原始位运算是为了取得连续签到的天数，但是我们只需返回签到总天数即可
        return Result.ok(bitField.get(0).intValue());
    }

    private User genUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(RedisConstants.DEFAULT_NICKNAME_PREFIX + phone);
        return user;
    }
}
