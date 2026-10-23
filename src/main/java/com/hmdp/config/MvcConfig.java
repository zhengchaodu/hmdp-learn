package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
    // 这里是被configuration注解标记的类，所以spring容器会扫描到这个类并创建一个MvcConfig对象
    // 因此可以在这个类中使用@Autowired、@Resource注解注入StringRedisTemplate对象
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
        //登录拦截器，拦截所有请求，除了登录、发送验证码、商铺信息、商铺类型、上传图片、秒杀信息
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/blog/hot",
                        "/shop/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/voucher/**"
                ).order(1);
        // token刷新拦截器
        // 通过order(0)设置优先级，保证RefreshInterceptor在LoginInterceptor之前执行
        registry.addInterceptor(new RefreshInterceptor(stringRedisTemplate))
                .addPathPatterns("/**").order(0);
    }
}
