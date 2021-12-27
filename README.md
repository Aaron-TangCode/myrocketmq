# myrocketmq



## RocketMQ源码目录结构
- 1）acl：权限控制模块。
- 2）broker：broker模块（broker启动进程）。
- 3）client：消息客户端，包含消息生产者和消息消费者相关类。
- 4）common：公共包。
- 5）dev：开发者信息（非源码）。
- 6）distribution：打包分发目录（非源码）。
- 7）example：RocketMQ示例代码。
- 8）filter：消息过滤相关基础类。
- 9）logappender：日志实现相关类。
- 10）logging：自主实现日志相关类。
- 11）namesrv：NameServer实现相关类（NameServer启动进程）。
- 12）openmessaging：消息开放标准，已发布。
- 13）remoting：远程通信模块，基于Netty。
- 14）srvutil：服务器工具类。
- 15）store：消息存储实现相关类。
- 16）style：checkstyle相关实现。TODO:checkstyle是什么，有待了解
- 17）test：测试相关类。
- 18）tools：工具类，监控命令相关实现类。



## RocketMQ

- 追求：简单高效
- 核心功能：发送消息、消费消息、存储消息、路由发现
- 基于发布订阅机制
- 容忍设计缺陷，将问题交给RocketMQ的使用者来解决。比如：如何保证消息被消费者消费，且只消费一次？RocketMQ的做法是：不保证消息只被消费一次，但保证消息一定被消息，所以会有重复消费的问题
- 设计目标
  - 架构模式：其实就是核心功能的内容
  - 顺序消息：保证消息严格有序
  - 消息过滤：RocketMQ的消息过滤是由服务端和消费端共同完成的。
  - 

## NameServer

- NameServer之间互不通信
- 不追求强一致性，追求最终一致性
- 功能：管理topic路由信息