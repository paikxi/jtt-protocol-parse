# jtt-protocol-parse

可以自定义协议的国标报文解析工具。主要功能为输入一段完整的 hex 报文，输出：

1. **报文中文全名**（如「GB/T 32960.3 新能源汽车国标 · breton · 车辆登录」）
2. **按协议字节顺序的单流字段树**：字段中文名 + 工程值 + 单位/枚举中文 + 原始 hex 对照，直到结尾

方便开发或运维人员实时看到明文数据

设计特点：**零数据库**（协议描述 = 只读 YAML，随 Git 管理）、解析引擎无状态纯计算（`hex in → ParseResult out`）、前端零构建（Vue 3 本地 vendor，无 npm/Vite）。厂商与版本扩展主要靠加 YAML 文件，不动 Java。

完整设计方案见 [docs/设计方案.md](docs/设计方案.md)（唯一权威文档），协议字段配置见 [docs/配置指南.md](docs/配置指南.md)。

## 环境要求

| 项 | 要求 |
|---|---|
| JDK | 17+（pom 已覆盖 JDK 21 所需的 Lombok 1.18.42） |
| Maven | 3.8+ |
| 数据库 | 不需要 |
| Node/npm | 不需要（前端为零构建单页，`web/vendor/vue.global.prod.js` 本地引入） |

## 快速开始

```bash
# 1. 拉代码
git clone git@github.com:kuangxianxi/jtt-protocol-parse.git
cd jtt-protocol-parse

# 2. 跑测试确认环境（31 个回归用例）
mvn test

# 3. 启动
mvn spring-boot:run
```

启动后浏览器访问 **http://localhost:8080（同时在仓库根目录运行，前端静态页从 `web/` 目录加载）。

### 打包运行

```bash
mvn -DskipTests package
java -jar target/jtt-protocol-parse-0.0.1-SNAPSHOT.jar
```

> 注意：静态资源配置为 `file:web/`（相对工作目录），jar 方式运行请**在仓库根目录执行**，保证 `./web` 存在。

### 修改端口

`src/main/resources/application.yml` 中 `server.port`（默认 8080）。

## 使用方式

### 页面（推荐）

打开 http://localhost:8080/ ，协议/版本/厂商下拉会自动从描述文件生成；粘贴 hex 点「解析」即可。支持一次粘贴多条（回车分隔），单条失败不影响其他。

示例报文（车辆登录，可直接粘贴）：

```
232301FE56494E31323334353637383930414243440100081A0A14112300000195
```

### REST API

```bash
# 支持的协议清单（协议 → 版本 → 厂商）
curl http://localhost:8080/api/protocols

# 解析
curl -X POST http://localhost:8080/api/parse \
  -H "Content-Type: application/json" \
  -d '{"protocol":"gc-32960","version":"2016","vendor":"breton","hex":"232301FE56494E31323334353637383930414243440100081A0A14112300000195"}'
```

响应要点：`nodes` 为**单流节点列表**（报文头 → 数据体 → 校验码，顺序即协议字节顺序）；未匹配场景（协议描述未登记 / 未知命令字 / 帧不可识别 / 任何剩余字节）统一收口为「未知报文」节点（展示报文原文 + 原因），issue 照记不中断整体解析；`issues` 随 API 返回，页面不渲染。

## 项目结构

```
jtt-protocol-parse
├── docs/                          # 文档：设计方案（权威）+ 配置指南
├── web/                           # 前端零构建单页（Spring 静态资源 file:web/）
│   ├── index.html
│   └── vendor/vue.global.prod.js
├── src/main/java/com/xx/jttprotocolparse/
│   ├── JttProtocolParseApplication.java
│   ├── core/                      # 解析内核，纯 Java 零 Spring 依赖
│   │   ├── descr/                 #   DescriptorRegistry：YAML 加载与加载期校验
│   │   ├── model/                 #   协议描述模型（YAML 直接映射的目标）
│   │   ├── frame/                 #   帧层：FrameAdapter（拆包/长度/校验）+ GB32960 实现
│   │   ├── engine/                #   ParseEngine：hex → 拆帧 → 命令字路由 → 子报文路由 → 字段解析
│   │   ├── codec/                 #   FieldCodec：字段解码（scale/offset/map/bits/segments/invalid…）
│   │   ├── result/                #   ParseResult / Node / Issue / IssueCode
│   │   └── hex/                   #   HexUtil
│   └── web/                       # REST 薄层（只装配 core）
│       ├── ApiController.java     #   POST /api/parse、GET /api/protocols
│       └── BeanConfig.java
├── src/main/resources/
│   ├── application.yml            # 端口与静态资源目录
│   └── protocols/                 # 协议描述（YAML，随 Git 管理，永不入库）
│       ├── protocols.yaml         #   清单：协议 → 版本 → 厂商
│       └── gc-32960/2016/breton.yaml
└── src/test/java/com/xx/...       # 回归样例（测试 hex 与页面样例共用）
```

## 协议配置（YAML）

**改报文内容 = 只改 YAML + 补回归样例，不动 Java**；新增协议才写新的 `FrameAdapter`。

1. **登记**：`src/main/resources/protocols/protocols.yaml` 按「协议 → 版本 → 厂商」登记；解析时三者必须同时命中且对应文件存在，否则走「协议描述未登记」的未知报文收口：

```yaml
protocols:
  gc-32960:
    label: GB/T 32960.3 新能源汽车国标
    versions:
      "2016":
        vendors:
          - breton
```

2. **厂商文件**：每个厂商**一份完整独立文件**（不做 overlay），如 `protocols/gc-32960/2016/<厂商>.yaml`。骨架示意：

```yaml
protocol: gc-32960
version: "2016"
label: GB/T 32960.3 新能源汽车国标

"0x01":                   # 命令字
  name: 车辆登录
  body:                   # 字段列表（节点标识用 label，无 id）
    - label: 登录时间
      type: dec-time
    - label: 登录流水号
      type: u2

"0x02":                   # 实时信息上报：stream 型
  name: 实时信息上报
  isStream: true
  body:                   # 可选前置字段（如采集时间）
    - label: 数据采集时间
      type: dec-time
  subtype:                # 数据类型(1B) → 信息体字段列表
    "0x01":
      name: 整车数据
      body: [ ... ]
```

3. **字段能力**（收敛于设计方案 §5.3 清单，详见 [docs/配置指南.md](docs/配置指南.md)）：

| 能力 | 写法 |
|---|---|
| 数值/字符串/原样/时间 | `type: u1/u2/u3/u4/s1/s2/bcd-time/dec-time/ascii/gbk/raw` |
| 变长 | `length: rest` 或算式 `"$驱动电机个数*14"` |
| 缩放/偏移/单位 | `scale: 0.1` / `offset: 1000` / `unit: km/h` |
| 枚举 / 特殊值 | `map: { "0x01": 启动态 }` / `invalid: { "0xFFFF": 异常 }`（互斥） |
| 位表 / 位段 | `bits`（掩码型，十进制位号）/ `segments: [{label, start, width, values}]`（互斥） |
| 循环块 | `type: group, count: "$驱动电机个数", fields: [...]`（可嵌套引用前文 label） |

规则约束：同字段 `map`↔`invalid`、`bits`↔`segments` 冲突直接**加载期失败**；不确定的字段用 `type: raw` 保留原样并核对标准，禁止凭记忆编造。

## 测试

```bash
mvn test
```

- `ParseEngineTest`：真实样例帧回归（登录 / 实时上报全量子类型 / 坏校验 / 多条粘贴 / 未知命令 / 未匹配收口），样例 hex 与 web 页面共用，**改 YAML 必须同步补样例**，防止配置改动悄悄破坏解析；
- `FieldCodecTest`：字段解码与 DSL 能力（invalid / segments / 浮点精度等）；
- `DescriptorRegistryTest`：加载期校验（互斥冲突 / 越界 / 完整独立配置）。

## Git 提交规范

提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)：`<type>(<scope>): <subject>`。

| type | 含义 |
|---|---|
| feat | 新增功能 |
| fix | 修复 bug |
| docs | 文档修改 |
| style | 格式调整（不影响代码运行） |
| refactor | 重构 |
| perf | 性能优化 |
| test | 测试相关 |
| chore | 构建过程或辅助工具变动 |

示例：`feat(dsl): 新增 segments 位段映射`。每次提交保持 `mvn test` 全绿。
