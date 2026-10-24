package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
// 也可以通过继承Shop（为例）的方式来实现RedisData类
// 这样可以直接使用Shop的属性，而不需要再定义data属性。
// 但是这样做的缺点是，RedisData类和Shop类耦合在一起，如果Shop类发生变化，RedisData类也需要修改。
// 我们的业务场景中，RedisData类是一个通用的类，可以存储任何类型的数据，所以不建议继承Shop类。
// 使用Object类型的data属性，可以存储任何类型的数据，灵活性更高。
