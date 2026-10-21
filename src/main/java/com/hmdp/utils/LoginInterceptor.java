package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;

public class LoginInterceptor implements HandlerInterceptor {
    // 由于LoginInterceptor在MvcConfig.java中是手动new出来的
    // 不是spring容器管理的bean，所以不能使用@Autowired、resource注解注入UserHolder
    // 加component也不行，因为扫不到这里来
    // 只能用构造器来注入stringTemplate
    private StringRedisTemplate stringRedisTemplate;
    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 获取请求头中的token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            // 不存在，拦截，返回401状态码
            response.setStatus(401);
            return false;
        }
        // 2.基于token获取redis中的用户
        Map<Object,Object> userMap = stringRedisTemplate.opsForHash()
                .entries(RedisConstants.LOGIN_USER_KEY + token);
        // 3.判断用户是否存在
        if (userMap.isEmpty()) {
            // 4.不存在，拦截,返回401状态码
            response.setStatus(401);
            return false;
        }
        // 5.将查询到的HashMap对象转换为UserDTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap,new UserDTO(),false);
        // 6.存在，保存用户信息到ThreadLocal
        UserHolder.saveUser(userDTO);
        // 7.刷新token有效期
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, java.util.concurrent.TimeUnit.MINUTES);
        // 8.放行
        return true;
    }
    @Override
    public void afterCompletion(javax.servlet.http.HttpServletRequest request, javax.servlet.http.HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户，防止内存泄漏
        UserHolder.removeUser();
    }
}
