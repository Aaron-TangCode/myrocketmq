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

  - 消息存储：重要的2个维度：消息堆积能力和消息存储性能，就是能存多少消息和存消息有多快。如何实现消息存储的高性能呢？通过引入内存映射机制，所有主题的消息按顺序存储在同一个文件中

  - 消息高可用：前3种，如果开启同步刷盘模式，可以保证数据不丢失。后2中情况属于单点故障，一旦发生，所有数据都会丢失。如果开启了异步复制机器，能保证只丢失少量消息。最后单点故障，开启异步复制机制，是如何实现保证只丢失少量信息的，这里我有疑问，有待解决。

    - Borker异常崩溃
    - 操作系统崩溃
    - 机器断电，但能立即恢复供电
    - 机器无法开机，可能是CPU、主板等问题导致
    - 磁盘损坏

  - 消息低延迟：用长轮询的方式来实现消息推送给消费者。

  - 确保消息至少能被消息一次：这个是通过ACK确认机制实现的

  - 回溯消息：什么是回溯消息？是指已被消费的消息，需要被重新消费一次。RocketMQ支持按照时间向前或向后回溯消息，时间可以精确到毫秒。

  - 消息堆积：RocketMQ支持消息的存储，但不是无限期的存储，默认过期时间是3天

  - 定时消息：消息发送到broker后，不能被立即消费，需要等到特定时间点后，消息才能被消费。但RocketMQ不支持任意时间，因为支持任意时间，需要对消息进行排序，排序消息会带来较大的性能消耗

  - 消息重试机制：消息重试是指消息被消费时，如果发生异常，RocketMQ支持消息重新投递。

    

## NameServer

- NameServer之间互不通信
- 不追求强一致性，追求最终一致性
- 功能：管理topic路由信息

##### 架构设计图

![image-20211229100953191](/Users/aaron/Library/Application Support/typora-user-images/image-20211229100953191.png)



##### 如果某一台消息服务器宕机了，生产者如何在不重启服务的情况下感知呢？



##### 消息生产者如何知道消息要发送到哪台服务器？

> Broker消息服务器在启动时会向NameServer注册，消息生产者在发送消息之前先从NameServer获取Broker服务器的地址列表，然后根据负载算法从列表中选择一台消息服务器发送消息



##### NameServer和Broker的心跳

> NameServer和每台Broker服务器保持长连接，并间隔10s检测Broker是否存活。如果检测到Broker宕机，没心跳了，就会把broker从路由注册表移除。但是路由不会立马通知消息生产者，为什么要这样子设计呢？为了降低NameServer的实现复杂度。因此需要在消息发送端提供容错机制来保证消息发送的高可用。Broker发送心跳包时，包含自身创建的topic路由等信息



##### NameServer根据什么来判定Broker宕机？

> 如果120s内，Broker都没心跳响应，就会被NameServer判定为宕机。
>
> 这里特别强调一下：NameServer是每10s检测心跳，而Broker是每30s发送一次心跳给NameServer



##### 消息客户端和NameServer是如何交互的？

> 消息客户端会每隔30s，从NameServer中获取路由信息



##### 如果保证NameServer的高可用？

> 部署多台NameServer即可，NameServer之间互不通信，会造成短时间内，NameServer的数据不一致，但无关重要，无非就是造成消息短暂的发送不均衡。



##### NameServer路由注册、故障剔除

> NameServer的主要作用是为了消息生产者和消息消费者提供topic的路由信息，以及管理broker节点，包括路由注册和路由发现、路由剔除

##### 小技巧

- 在启动NameServer时，可以先使用./mqnameserver -c configFile -p 命令打印当前加载的配置属性

##### NameServer-路由元信息

```java
private final HashMap<String/* topic */, List<QueueData>> topicQueueTable;//topicQueueTable:topic消息队列的路由信息，消息发送时根据路由表进行负载均衡
    private final HashMap<String/* brokerName */, BrokerData> brokerAddrTable;//brokerAddrTable:broker基础信息，包含brokername，所属集群名称，主备Broker地址
    private final HashMap<String/* clusterName */, Set<String/* brokerName */>> clusterAddrTable;//clusterAddrTable:broker集群信息，存储集群中所有broker的名称
    private final HashMap<String/* brokerAddr */, BrokerLiveInfo> brokerLiveTable;//brokerLiveTable：broker状态信息，nameserver每次收到心跳包时，会替换该信息
    private final HashMap<String/* brokerAddr */, List<String>/* Filter Server */> filterServerTable;//filterServerTable:Broker上的FilterServer列表，用于类模式消息过滤。类模式过滤机制在4.4及以后版本被废弃

```

**总结**

```java
/**
 * 一个集群有多个Broker
 * 一个Broker有多个主题topic
 * 相同主题的不同topic可以在不同的broker内？？？待确定
 * 一个topic默认有4个写队列和4个读队列
 * brokername相同的组成主从架构，brokerid=0,表示主，brokerid>0表示从节点
 */
```

##### 路由注册

###### 1、broker发送心跳包

###### 2、nameserver处理心跳包
