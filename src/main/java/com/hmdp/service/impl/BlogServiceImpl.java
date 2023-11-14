package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private UserServiceImpl userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;

    @Override
    public Result queryBlogById(Long id) {
        //1.查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("评价不存在或已被删除");
        }
        //2.查询blog有关的用户
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach((blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        }));
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        //1. 获取当前用户信息
        Long userId = UserHolder.getUser().getId();
        //2. 如果当前用户未点赞，则点赞数 +1，同时将用户加入set集合
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            //点赞数 +1
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            //将用户加入set集合
            if (success) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
            //3. 如果当前用户已点赞，则取消点赞，将用户从set集合中移除
        } else {
            //点赞数 -1
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
            if (success) {
                //从set集合移除
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    /*@Override
    public Result likeBlog(Long id) {
        //1. 获取当前用户信息
        Long userId = UserHolder.getUser().getId();
        //2. 如果当前用户未点赞，则点赞数 +1，同时将用户加入set集合
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Boolean isLiked = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        if (BooleanUtil.isFalse(isLiked)) {
            //点赞数 +1
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            //将用户加入set集合
            if (success) {
                stringRedisTemplate.opsForSet().add(key, userId.toString());
            }
            //3. 如果当前用户已点赞，则取消点赞，将用户从set集合中移除
        }else {
            //点赞数 -1
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
            if (success){
                //从set集合移除
                stringRedisTemplate.opsForSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }*/
    private void isBlogLiked(Blog blog) {
        UserDTO userDTO = UserHolder.getUser();
        if (userDTO == null) {
            return;
        }
        Long userId = userDTO.getId();
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        // zrange key 0 4 查询前五个元素
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        //为空则表示没人点赞
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        //将ids用“，”拼接，SQL语句查询出来的结果并不是按照我们期望的方式进行排
        //所以我们需要用order by field来指定排序方式，期望的排序方式就是按照查询出来的id进行排序
        String idsStr = StrUtil.join(",", ids);
        //select * from tb_user where id in (ids[0], ids[1] ...) order by field(id, ids[0], ids[1] ...)
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids)
                .last("order by field(id," + idsStr + ")")
                .list().stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);

    }

    @Override
    public Result saveBlog(Blog blog) {
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        save(blog);
        //查找当前用户的所有粉丝
        List<Follow> follows = followService.list(new LambdaQueryWrapper<Follow>().eq(Follow::getFollowUserId, user.getId()));
        for (Follow follow : follows) {
            Long userId = follow.getUserId();
            String key = RedisConstants.FEED_PREFIX + userId;
            //此处的userId是这个博文的创建人，推送至redis
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        return Result.ok(blog.getId());

    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.查询登录用户的收件箱,推送时的key是前缀加上关注者(此处就是登录用户)的id
        String key = RedisConstants.FEED_KEY + userId;
        //3.滚动分页查询 zreversebyscore key max min limit offset count
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //4.非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //5.解析数据：blogId，minTime(时间戳)，offset
        long minTime = 0;
        int os = 1;
        List<Long> ids = new ArrayList<>(typedTuples.size());
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            //5.1获取id
            ids.add(Long.valueOf(tuple.getValue()));
            //6.获取redis score 时间戳
            //因为redis取出的是按照时间戳从大到小排序的，最后一个是最小的
            //offset是集合里面score等于最小值的元素的个数
            Long timestamp = Long.valueOf(tuple.getScore().longValue());
            if (minTime == timestamp) {
                //9 8 6 6 5 4，取三个 第一次 9,8,6 第二次从6开始取三个，因为有两个6，取出来的结果是6,6,5并不是6,5,4，
                //因此需要设置偏移量防止重复存在的时间戳score对取值造成影响
                //最小肯定有一次是本身，至少得偏移一位
                //7,6,6,6,6,6,3,2,1
                //第一次 7，6,6  min->6, offset->2
                //第二次 6,6,6  序列还剩3,2,1，因为偏移了2，所以此时是从第一个6向后偏移两个，也就是第三个6开始取三个
                //第三次 3,2,1 全部取出
                os++;
            } else {
                minTime = timestamp;
                os = 1;
            }
        }
        //mysql的in不能按照指定的顺序进行查询，因此得手写sql来实现按顺序查询
        String idsStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("order by field(id," + idsStr + ")").list();
        //填充每个blog中的其他信息 1)blog发布者的信息，queryBlogUser函数实现
        // 2)当前用户是否给改blog点赞 isBlogLike实现
        // todo 这两个方法名取的不好，可以改一下,因为看不出来具有填充信息的功能
        for (Blog blog : blogs) {
            queryBlogUser(blog);
            isBlogLiked(blog);
        }
        //封装结果并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }
}
