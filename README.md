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

### 架构设计图

![image-20211229100953191](/Users/aaron/Library/Application Support/typora-user-images/image-20211229100953191.png)



### 问题

##### 1、如果某一台消息服务器宕机了，生产者如何在不重启服务的情况下感知呢？



##### 2、消息生产者如何知道消息要发送到哪台服务器？

> Broker消息服务器在启动时会向NameServer注册，消息生产者在发送消息之前先从NameServer获取Broker服务器的地址列表，然后根据负载算法从列表中选择一台消息服务器发送消息



##### 3、NameServer和Broker的心跳

> NameServer和每台Broker服务器保持长连接，并间隔10s检测Broker是否存活。如果检测到Broker宕机，没心跳了，就会把broker从路由注册表移除。但是路由不会立马通知消息生产者，为什么要这样子设计呢？为了降低NameServer的实现复杂度。因此需要在消息发送端提供容错机制来保证消息发送的高可用。Broker发送心跳包时，包含自身创建的topic路由等信息



##### 4、NameServer根据什么来判定Broker宕机？

> 如果120s内，Broker都没心跳响应，就会被NameServer判定为宕机。
>
> 这里特别强调一下：NameServer是每10s检测心跳，而Broker是每30s发送一次心跳给NameServer



##### 5、消息客户端和NameServer是如何交互的？

> 消息客户端会每隔30s，从NameServer中获取路由信息



##### 6、如果保证NameServer的高可用？

> 部署多台NameServer即可，NameServer之间互不通信，会造成短时间内，NameServer的数据不一致，但无关重要，无非就是造成消息短暂的发送不均衡。



##### 7、NameServer路由注册、故障剔除

> NameServer的主要作用是为了消息生产者和消息消费者提供topic的路由信息，以及管理broker节点，包括路由注册和路由发现、路由剔除

##### 小技巧

- 在启动NameServer时，可以先使用./mqnameserver -c configFile -p 命令打印当前加载的配置属性

### NameServer路由注册、故障剔除

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

- 详情：BrokerController#start

###### 2、nameserver处理心跳包

- 详情：RouteInfoManager#registerBroker

  



##### 路由删除

- 有2个路由删除的入口
  - 1、NameServer每10秒定时扫描brokerlivetable,检测上次心跳包与当前系统时间的时间戳，如果时间戳大于120s,则移除broker信息
  - 2、Broker在正常关系的情况下，会执行unregisterbroker命令。
- 路由删除需要维护的信息：brokerlivetable,topicqueuetble,brokeraddrtable,filterservertable等信息
- 详情：
  - RouteInfoManager#scanNotActiveBroker
  - RouteInfoManager#onChannelDestroy

##### 路由发现

- 路由发现是非实时的
- 当topic的路由发生变化后，nameserver是不会主动推送给客户端的。是客户端定时拉取topic的最新路由。
- 详情：
- 为DefaultRequestProcessor#getRouteInfoByTopic



**设计缺陷：**

NameServer路由发现与删除机制就介绍到这里了，我们会发现这种设计存在这样一种情况：NameServer需要等Broker失效至少120s才能将该Broker从路由表中移除，如果在Broker故障期间，消息生产者根据主题获取到的路由信息包含已经宕机的Broker，就会导致消息发送失败。那么这种情况怎么办，岂不是消息发送不是高可用的？让我们带着这个疑问进入RocketMQ消息发送的学习。



思考一下：其实路由发现、路由删除、路由注册，本质就是对路由元信息的增删改查。

- 路由发现，就是对路由元信息的查询
- 路由删除，就是对路由元信息的删除
- 路由注册，就是对路由元信息的增加或修改

## Producer

### RocketMQ消息发送

> RocketMQ有3种发送消息的方法：
>
> - 可靠同步发送
> - 可靠异步发送
> - 单向发送

##### RocketMQ消息结构(重点)



##### 消息生产者启动流转(重点)



##### topic路由机制

![image-20220105181207943](/Users/aaron/Library/Application Support/typora-user-images/image-20220105181207943.png)

描述：RocketMQ提供了自动创建主题（topic）的机制，消息发送者向一个不存在的主题发送消息时，向NameServer查询该主题的路由信息会先返回空，如果开启了自动创建主题机制，会使用一个默认的主题名再次从NameServer查询路由信息，然后消息发送者会使用默认主题的路由信息进行负载均衡，但不会直接使用默认路由信息为新主题创建对应的路由信息。



**注意**

RocketMQ的路由信息，持久化在Broker中。NameServer的路由信息来自Broker的心跳包，且存储在内存中。



##### 消息发送高可用设计

RocketMQ为了保证发送消息的高可用，引入了2个特性：

- 消息重试机制：顾名思义，就是消息发送失败后，会重新发送。默认重试2次。
- 故障规避机制：当消息第一次发送失败后，如果第二次发送消息还是发送到刚刚失败的broker，大概率还是会失败的。为了保证发送的成功率，在重试时，会尽量避开刚刚接收消息失败的broker，选择其他broker进行发送消息，从而提高消息发送的成功率。

![image-20220106102717742](/Users/aaron/Library/Application Support/typora-user-images/image-20220106102717742.png)

> 补充：消息重试机制，并不是任何时候都会重试的。在以下这种情况，就不会重新发送消息。情况：生产者如果发送消息时，选择自定义队列负载算法，这个时候，重试机制将失效。

##### 消息发送过程(重点)

![image-20220106104051416](/Users/aaron/Library/Application Support/typora-user-images/image-20220106104051416.png)

##### 批量消息发送(重点)



### 问题

RocketMQ消息发送需要考虑以下3个问题

##### 1）消息队列如何进行负载？

##### 2）消息发送如何实现高可用？

##### 3）批量消息发送如何实现一致性？

