# 工程化整改清单

本文档按优先级整理当前项目的工程化整改项，目标不是继续堆功能，而是先把关键链路做对、做稳、做清晰。

## P0 先修

### 1. 统一秒杀异步链路，只保留一套消息方案

问题：
- `src/main/resources/seckill.lua` 已经把订单写入 Redis Stream：`XADD stream.orders`
- `src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java` 当前实际又把订单投递到 RabbitMQ
- `src/main/java/com/hmdp/listener/SeckillVoucherListener.java` 消费的是 RabbitMQ
- `VoucherOrderServiceImpl` 中原有的 Redis Stream 消费代码被整段注释

影响：
- 同一业务链路存在两套消息方案，职责边界混乱
- Lua 脚本、Java 生产端、消费端不一致，后续维护极易出错
- 代码阅读成本高，真实运行语义不清晰

建议：
- 二选一，优先建议保留 RabbitMQ 或 Redis Stream 中的一套，不要混用
- 如果保留 RabbitMQ，则删除 Lua 中的 `XADD`，Lua 只负责资格校验和库存预减
- 如果保留 Redis Stream，则删除 RabbitMQ 发送、交换机、监听器相关实现
- 清理 `VoucherOrderServiceImpl` 中已经废弃的整段注释实现，收口成一条明确链路

涉及文件：
- `src/main/resources/seckill.lua`
- `src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`
- `src/main/java/com/hmdp/listener/SeckillVoucherListener.java`
- `src/main/java/com/hmdp/config/QueueConfig.java`

### 2. 修正秒杀消费端绕过事务与幂等保护的问题

问题：
- `src/main/java/com/hmdp/listener/SeckillVoucherListener.java` 直接 `save(voucherOrder)` 并扣库存
- 没有复用 `VoucherOrderServiceImpl#createVoucherOrder`
- 消费端没有显式做幂等校验、防重和事务收口

影响：
- 一人一单约束在消费侧可能失效
- 订单保存与库存扣减不在同一个稳定事务边界内
- 消息重复投递时容易产生脏数据

建议：
- 消费端只负责反序列化和调用应用服务，不直接操作表
- 将“查重、扣库存、落单”统一收口到一个事务方法
- 给订单表增加唯一约束，例如 `(user_id, voucher_id)`，把业务防重下沉到数据库层
- 明确消费确认策略，失败时支持重试或转死信，而不是静默落库

涉及文件：
- `src/main/java/com/hmdp/listener/SeckillVoucherListener.java`
- `src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`

### 3. 修复缓存重建锁释放错误

问题：
- `src/main/java/com/hmdp/utils/CacheClient.java` 在逻辑过期重建完成后调用 `unLock(key)`
- 实际加锁使用的是 `lockKey`

影响：
- 锁可能无法正确释放
- 热点 key 重建时会出现长时间锁占用
- 后续请求可能持续拿旧数据或频繁重建失败

建议：
- 将 `unLock(key)` 改为 `unLock(lockKey)`
- 为缓存重建补充并发测试，验证热点 key 在高并发下只触发一次重建

涉及文件：
- `src/main/java/com/hmdp/utils/CacheClient.java`

### 4. 修复登录态 ThreadLocal 生命周期问题

问题：
- `src/main/java/com/hmdp/interceptor/RefreshTokenInterceptor.java` 的 `afterCompletion()` 未清理 `UserHolder`
- `src/main/java/com/hmdp/controller/UserController.java` 的 `/logout` 只清理当前线程，不删除 Redis token

影响：
- Tomcat 线程复用时存在用户上下文泄漏风险
- 退出登录后旧 token 仍可能继续可用直到过期

建议：
- 在 `RefreshTokenInterceptor#afterCompletion` 中统一 `UserHolder.removeUser()`
- `/logout` 接口读取请求头 token，删除 Redis 中对应登录态
- 登录、登出、鉴权逻辑统一由拦截器和服务层管理，不在 Controller 中做局部清理

涉及文件：
- `src/main/java/com/hmdp/interceptor/RefreshTokenInterceptor.java`
- `src/main/java/com/hmdp/controller/UserController.java`
- `src/main/java/com/hmdp/utils/UserHolder.java`

### 5. 修复脚本文件命名不一致问题

问题：
- `src/main/java/com/hmdp/utils/SimpleRedisLock.java` 读取的是 `unlock.lua`
- 实际资源文件是 `src/main/resources/unLock.lua`

影响：
- Linux 环境区分大小写时会直接加载失败
- 分布式锁释放脚本不可用

建议：
- 统一资源文件命名，建议改为全小写 `unlock.lua`
- 同步修正 Java 侧脚本路径引用

涉及文件：
- `src/main/java/com/hmdp/utils/SimpleRedisLock.java`
- `src/main/resources/unLock.lua`

## P1 近期修

### 6. 移除硬编码配置与敏感信息

问题：
- `src/main/resources/application.yaml` 中硬编码了数据库地址、用户名、密码、Redis、RabbitMQ 本地地址

影响：
- 配置无法按环境切换
- 密钥直接进仓库，不符合最基本的安全规范

建议：
- 增加 `application-dev.yaml`、`application-test.yaml`、`application-prod.yaml`
- 默认配置改为环境变量占位，例如 `${DB_PASSWORD:}`
- 提供 `application-example.yaml` 或 `.env.example`

涉及文件：
- `src/main/resources/application.yaml`

### 7. 升级基础运行时并修正老旧配置

问题：
- `pom.xml` 仍使用 `Spring Boot 2.3.12.RELEASE`
- Java 版本声明为 `1.8`
- `application.yaml` 使用老旧驱动类 `com.mysql.jdbc.Driver`

影响：
- 依赖较旧，维护成本高
- 与当前本地工具链兼容性差
- 测试执行已经受到影响，当前环境 `Java 11 + Maven 4` 无法直接跑测试

建议：
- 先统一项目最低运行版本，再升级 Maven Wrapper 与 JDK
- 至少修正驱动类为 `com.mysql.cj.jdbc.Driver`
- 增加 `mvnw`，避免对宿主环境 Maven 版本强依赖

涉及文件：
- `pom.xml`
- `src/main/resources/application.yaml`

### 8. 给秒杀、缓存、登录补自动化测试

问题：
- 现有测试更像脚本工具或数据准备
- `src/test/java/com/hmdp/VoucherOrderControllerTest.java` 还会向资源目录写 token 文件

影响：
- 难以做稳定回归
- 关键并发链路没有自动验证

建议：
- 为登录、缓存、秒杀拆出明确的集成测试
- 将压测脚本与自动化测试分离
- 避免测试代码修改仓库内容或依赖人工准备数据

涉及文件：
- `src/test/java/com/hmdp/HmDianPingApplicationTests.java`
- `src/test/java/com/hmdp/ShopCacheTest.java`
- `src/test/java/com/hmdp/VoucherOrderControllerTest.java`

### 9. 给数据库层补约束与索引

问题：
- 代码里依赖应用层判重和业务逻辑控制，但未见数据库层约束说明

影响：
- 并发下容易因重复消费、补偿重试、人工脚本等产生脏数据

建议：
- 为 `tb_voucher_order(user_id, voucher_id)` 增加唯一索引
- 为关注关系、点赞、手机号等关键查询字段补索引
- 在 `src/main/resources/db/hmdp.sql` 中显式维护约束定义

涉及文件：
- `src/main/resources/db/hmdp.sql`

## P2 持续优化

### 10. 清理教学型注释和废弃实现

问题：
- 多个类中保留了大量大段注释代码和替代实现
- 典型位置包括 `ShopServiceImpl`、`VoucherOrderServiceImpl`

影响：
- 新接手的人很难判断实际生效逻辑
- 改动时容易误改到废弃分支

建议：
- 已废弃逻辑直接删除，保留必要的设计说明即可
- 将设计对比迁移到独立文档，而不是长期堆在业务类中

涉及文件：
- `src/main/java/com/hmdp/service/impl/ShopServiceImpl.java`
- `src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`

### 11. 统一常量、命名和过期时间语义

问题：
- 常量命名与实际单位不完全一致，例如 `LOGIN_USER_TTL = 36000L`，但代码里多处直接写 `30 MINUTES`
- 部分 key 前缀直接写在业务代码里，如 `follows:`

影响：
- 配置和实现容易漂移
- 读代码时难以判断真实过期策略

建议：
- 所有 Redis key、TTL 统一收口到 `RedisConstants`
- 常量命名带上单位，例如 `LOGIN_USER_TTL_MINUTES`
- 避免业务代码里出现裸字符串 key

涉及文件：
- `src/main/java/com/hmdp/utils/RedisConstants.java`
- `src/main/java/com/hmdp/service/impl/*.java`

### 12. 增加可观测性和失败告警

问题：
- 当前日志大多是开发期日志，没有围绕关键链路建立结构化信息

影响：
- 秒杀失败、消息积压、缓存重建异常难以快速定位

建议：
- 为登录失败、秒杀资格失败、消息消费失败、缓存重建异常增加结构化日志
- 后续接入 Micrometer/Prometheus 时，优先暴露库存扣减成功率、下单成功率、消息堆积等指标

涉及文件：
- `src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`
- `src/main/java/com/hmdp/listener/SeckillVoucherListener.java`
- `src/main/java/com/hmdp/utils/CacheClient.java`

## 推荐整改顺序

1. 先统一秒杀异步链路，修复消费端事务与幂等。
2. 再修缓存锁释放、ThreadLocal 清理、脚本命名这些确定性 bug。
3. 然后处理配置外置、运行时升级、数据库约束和自动化测试。
4. 最后做代码清理、常量收口和可观测性补强。

## 交付标准

完成第一阶段整改后，至少应满足：
- 秒杀只有一套明确异步架构
- 下单链路具备事务边界、幂等保障、数据库唯一约束
- 登录态不会因线程复用泄漏
- 热点缓存重建锁可以正确释放
- 配置不再把敏感信息直接提交进仓库
- 自动化测试可在固定工具链中稳定运行
