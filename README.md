#### snmpV3-sdk——snmpV3协议sdk
##### 1、实现功能
- [x]  SnmpV3 `priv-auth`双重账户验证模式
- [x] SnmpV3协议消息收发模块
  - [x] SnmpSession
  - [x] SnmpSender(NIO)
  
##### 2、采用的线程模型及IO模型
*snmp协议采用UDP通信，UDP是TCP/IP协议栈传输层无连接协议，基于socket，可实行多路复用*

**基于udp的snmp协议IO模型设计**

* 一对一：SnmpSession，创建时绑定本地udp端和接收端，一个session就是一个逻辑链路，连接两端.
* 多对多: upd-channel NIO模式，SnmpSender + NioTransportMapping，本地起用多个多路复用器，并分别分配一个独立线程来轮询其选择器，
每个channel创建时会选择一个selector选择器来注册，这样就可同时监听大批量channel的IO事件，设计参考`Netty#NioEventLoop`.

**线程模型**

*一个特定协议的网络通信服务主要功能可分3部分：（1）网络IO；（2）协议数据编解码；（3）对解码后的协议数据进行业务处理。*
*从I与O两个视角看线程模型：*

* Output——发送

> * 同步模型：协议数据编码封装与发送动作由`send`操作调用者完成。缺点：发送动作是耗时阻塞任务，会阻塞调用线程直到`write`发送动作完成
> * 纯异步模型：`send`操作调用者只管将发送信息（包括：数据内容、接收方信息）封装为发送任务提交到后置任务队列即可，
>协议数据编码封装与发送动作的完成由后置线程接管，调用者立即返回
> * 半异步模型：`send`操作调用者负责协议数据编码封装和发送任务提交，发送动作的执行由后置线程完成，
>从而避免将调用者阻塞在耗时较高的实际发送操作上

* Input——接收

> * 同步模型：网络数据读操作与数据解码及解码后的协议数据处理在同一根线程内相继完成
> * 一对多——读操作线程-解码操作线程池隔离：1根IO线程 + 1个编解码线程池
> * 多对多——读操作线程池-解码线程池：1个IO线程池 + 1个编解码线程池

##### 3、使用示例
> *详见测试用例([SnmpSenderTest](src/test/java/io/snmp/sdk/core/sender/SnmpSenderTest.java))*
```java
SnmpSender snmpSender = SnmpSender.builder()
                .ioStrategy(IoStrategy.NIO)             //NIO策略
                .multi(1)                               //IO线程组线程个数（决定起多少selector选择器）
                .workerPool("snmp-msg-process-pool",   //后置异步解码线程池
                        1, 3,
                        Duration.ofSeconds(60),
                        1024
                )
                .retry(0)                               //失败重试次数
                .reqTimeoutMills(800)                   //请求超时时长
                .nonRepeaters(0)                        //PDU#nonRepeaters,保持默认0
                .maxRepetitions(24)                     //PDU#maxRepetitions
                .usmUser(Arrays.asList(                 //snmpV3 USM认证账户信息注册
                        new UsmUserEntry("udp:192.168.1.1/161",
                                "user0",
                                UsmEncryptionEnum.SHA, "123456789",
                                UsmEncryptionEnum.AES128, "123456789"
                        ))
                )
                .build();
```
