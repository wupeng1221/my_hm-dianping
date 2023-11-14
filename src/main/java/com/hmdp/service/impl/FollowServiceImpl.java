package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        String key = RedisConstants.FOLLOWING_USERS_PREFIX + UserHolder.getUser().getId();
        // 获取登录的用户id
        Long userId = UserHolder.getUser().getId();
        if (BooleanUtil.isFalse(isFollow)) {
            // false,表示还未关注，关注，新增数据
            Follow follow = new Follow()
                    .setFollowUserId(followUserId)
                    .setUserId(userId);
            boolean success = save(follow);
            //实现共同关注,关注的同时也将数据写入redis
            if (success) {
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            // true,已经关注，取关，删除数据
            boolean success = remove(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getUserId, userId)
                    .eq(Follow::getFollowUserId, followUserId));
            //取关成功从redis删除对应的关注数据
            if (success) {
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result followOrNot(Long followUserId) {
        //查询是否关注
        Long userId = UserHolder.getUser().getId();
        Follow follow = getOne(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getFollowUserId, followUserId)
                .eq(Follow::getUserId, userId));
        return Result.ok(follow != null);
    }

    @Override
    public Result followCommons(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        String userKey = RedisConstants.FOLLOWING_USERS_PREFIX + userId;
        String followUserKey = RedisConstants.FOLLOWING_USERS_PREFIX + followUserId;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(userKey, followUserKey);
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> commonsList = intersect.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());
        List<UserDTO> userDTOS = listByIds(commonsList).stream()
                .map((user) -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);

    }
}
