# 服务器任务管家

`task_monitor.py` 是独立的常驻服务，监控本机 Codex app-server 可见的持久任务。
它不依赖某个模型回合一直等待；关闭聊天页面不影响扫描。服务可开机启动，连接中断后继续扫描，
未完成的检查保存在 SQLite 中。只监控服务器任务，不读取 Windows 本机或其他主机的任务。

启用时间之前结束的任务不自动重跑。默认跳过临时对话和管家自身；可显式配置排除的任务。
每 20 秒通过已核验的本地 Unix socket 查询状态及完成回合，并用 `(thread_id, turn_id)` 去重。
支持桌面远程和手机客户端的两个 app-server；观察别人的任务不会调用 `thread/resume`。
安装手机对话转发服务后，扫描配置直接连接两个后端；手机入口按对话所属实例转发，
用户可以从桌面和手机进入同一个管家对话，详见 `THREAD_GATEWAY.md`。

新完成回合在持久的内部审查线程中检查，该线程通过 `attachToSource` 关联管家，普通聊天列表不显示。
用户看到的「服务器任务管家」保留自然交流和原生提问，不再连续写入后台审查 JSON。
即使管家正在等待用户回答，内部审查仍能工作。内部线程使用独立只读工作区，禁用管家管理工具。
普通事实问答及感谢等在无执行记录、未完成事项或异常时可以本地跳过；疑似操作请求仍会审查。

检查读取最近最多 32 轮的用户/助手摘录、工具退出码和文件变更数量。只按具体实体关联其他窗口，
保留发言所属任务及用户身份；共享工作目录本身不能成为跨项目转移授权的理由。
对旧提醒，当原任务有更新时撤回旧事件，由新事件继续审查；仅相关上下文变化时重审一次，
上下文未变化不反复调用模型。原任务已经向用户提出的问题不应在管家重复询问。

证据分为 `reported_only`（仅助手汇报）和 `tool_observed`（已观察工具执行），二者都不等于完整独立验收。
服务只读核对记录工作区内明确提及的产物元数据、APK 哈希及 Android XML 测试报告；拒绝越界符号链接。
测试报告标记是否晚于当前回合开始；命令退出码区分测试、构建、语法、diff 和其他命令。
不执行模型提供的命令，不独立 SSH、不重跑付费生成或任意部署。远端实时状态、需求覆盖及真机效果
仍须明确证据；没有证据时让原任务在已有授权内核实。`done` 表示关闭本轮事件，不代表全部验收通过。
输出包括事件编号、判断、摘要、跟进提示或问题，原始工具输出和凭据不写进服务日志。

当判断仍有明确的已授权事项未完成，服务先检查所有已知实例上的状态、最新回合及原任务的队列，
再通过 `thread/queue/add` 和 `thread/queue/start` 启动跟进。已有新任务/用户队列优先。
不调用 `turn/start` 去干预原任务的活动回合，不替用户回复任何审批或问题，也不提高原任务权限。
原任务如果已卸载或属于没有控制连接的 CLI 进程，只能检查并标注需处理，不强行接管。

每条自动跟进链默认最多 2 次，每天最多 20 次跟进、100 次检查。超过限制保留待处理状态，
避免模型互相驱动无限循环。中断/失败的原任务不会自动重试；确实需要重试时由用户在管家对话
回答相应问题。`ask_user` 不再只写入数据库：服务在管家空闲且用户队列为空时提交一条通知，
由原生 `request_user_input` 显示可回答卡片；同一时刻只显示一个问题，不设默认答案。
“继续核实”只批准只读核实，“暂不处理”关闭对应提醒，也可输入自己的具体决定。
自由回答重新交给内部审查解释，仍受原任务范围、权限和最新用户工作的约束。

问题交付及状态持久保存，重启后通过准确的回合/队列/通知全文核对；未知响应不盲目补发。
只有在管家权威 rollout 中匹配通知回合、原生工具、问题编号、调用编号及实际非空工具回复，
才认为收到用户回答。助手文字、JSON、自行打印的 exec 输出都不算授权。
旧问题撤回时只取消自己的队列条目，或中断内容完全未被用户改变的服务通知；不打断真实用户回合。
正常聊天回答也可以用私有控制工具保存决定，写入校验拒绝后台审查和服务通知伪装的用户输入。
决定取决于模型，无法保证所有语义判断都正确；跟进仍受原任务的权限和审批约束。

请求发出前先保存状态。连接丢失导致请求结果不明时，优先按事件标记核对历史；不能确认的
请求不会盲目重发，而是显示待处理。已提交的队列只按已知 ID 启动一次；用户已启动的同一
条目按标记关联其后续回合，重启后仍保留自动跟进次数。

## 配置与运行

用户可以直接在同一个「服务器任务管家」对话中正常聊天。例如：

- “以后只汇报，不要自动继续。”
- “部署和发布前先问我；普通修复按原授权继续。”
- “以后判定完成时，更重视原任务给出的验收证据。”
- “只盯这个服务器上的小说项目。”
- “暂停监控。” / “恢复监控。”
- “现在按什么规则决定是否跟进？” / “为什么刚才继续了那个任务？”

主动聊天使用自然语言回复。明确的偏好变化通过管家私有的 MCP 管理工具保存到独立的
`preferences.json`，带版本和最近 30 次变化记录；只有工具成功后才能答复已保存。
工具只接受白名单字段，不能修改连接、凭据、监控对话身份或服务配置。
列表及文字字段更新时先读取原值并合并；版本冲突要求重新读取，不覆盖新变化。
不明确的指代只澄清一次，不把方案讨论、引用内容或普通问答保存成偏好。

后台每个周期合并最新偏好，每次审查显式附带当前规则；自然对话的说明也会在空闲时刷新。
“已保存”和“后台已生效”分别以保存版本及 `status.json` 中的 `preference_revision` 核对。
偏好调整在下一周期生效，重启后保留。暂停不阻止用户继续聊天、改偏好或恢复监控。
切换为只汇报后，尚未启动的自动跟进会撤掉自己的队列条目；不撤销已经开始的原任务。

管家对话保持只读的终端权限，专用 stdio MCP 服务只提供偏好、状态、事件决定等 6 个操作，
由后台校验后写入自己的运行数据。它只配置在管家线程，六个工具采用该服务范围内的 approve
模式；不修改全局 MCP 或原任务的审批策略，不提供任意命令或任意路径写入。
偏好及事件决定写入前，工具会读取管家当前真实用户回合，拒绝后台审查回合及没有活动用户回合的请求。
写操作通过所有已配置后端的 `thread/loaded/list` 查找唯一的实际持有者，再从该后端读取当前回合。
不再固定使用 `monitor_socket` 上可能过时的历史快照；整个校验只读，不恢复或抢占其他任务。
保存前再次核对归属及当前回合，交接期间拒绝写入。无持有者、多个持有者、后端不可用、
已结束的回合、后台审查、服务通知及自动跟进均不能授权保存。
错误返回稳定的 `code` 和不含私密路径的 `message`，区分会话归属、用户指令校验、版本冲突、
参数或值无效及存储错误。笼统的 `ValueError` 不得直接解释成“参数校验错误”。
后台审查仍按事件指令只返回结构化判断，不调用工具；原任务的权限没有改变。
决策偏好及 `ask_before` 是提供给模型的语义规则，实际执行仍依赖模型判断，
硬性限额、监控范围、是否允许自动跟进则由服务再次检查。

管家使用运行目录下独立的 `chat/` 工作区，项目配置持久保存这 6 个私有工具和只读权限。
仅用 `thread/resume.config` 注入工具会在客户端普通重开后丢失，不能作为持久配置。
该工作区应位于已受信任的项目内；安装时用 `config/read` 核对项目配置层实际启用，
并在无额外配置的普通恢复后核对 `mcpServerStatus/list` 仍包含全部 6 个工具。
其他任务的工作区及全局 MCP 设置不会因此改变。

`task_monitor_control.py` 保留为维护命令，也可由维护者执行：

```sh
python3 -B server/task_monitor_control.py --runtime codex/task-monitor/runtime show
python3 -B server/task_monitor_control.py --runtime codex/task-monitor/runtime set \
  --expected-revision 0 --changes '{"allow_followups":false,"ask_before":["deploy"]}' \
  --reason '用户要求只汇报，部署先问'
python3 -B server/task_monitor_control.py --runtime codex/task-monitor/runtime status
```

使用 `codex/task-monitor/runtime/config.json` 保存当前安装配置，整个运行目录已被项目现有
`/codex/*` 规则忽略。不要提交运行数据库、历史摘录或日志。

```json
{
  "runtime": "/absolute/repo/codex/task-monitor/runtime",
  "sockets": ["/verified/desktop.sock", "/verified/mobile.sock"],
  "monitor_socket": "/verified/monitor-owner.sock",
  "monitor_thread": "existing-supervisor-thread-id",
  "excluded_threads": ["bootstrap-control-thread-id"],
  "enabled": true,
  "allow_followups": true,
  "poll_seconds": 20,
  "max_reviews_per_day": 100,
  "max_followups_per_day": 20,
  "max_followups_per_chain": 2
}
```

`monitor_socket` 必须指向实际持有管家对话的实例。断线时任务继续记录为待检查；恢复后补查。
不要把不同服务器上未核验的 socket、凭据或任意网络地址加入配置。
`thread_ids` 可选，仅用于指定任务范围；省略时扫描所有符合条件的服务器任务。

```sh
python3 -B server/task_monitor.py --config codex/task-monitor/runtime/config.json --once
python3 -B server/task_monitor.py --config codex/task-monitor/runtime/config.json
```

安装时替换 `task-monitor.service.in` 的 `@USER@`、`@REPO@` 和 `@CONFIG@`，
将生成文件放在 `codex/task-monitor/`，核对后安装到 systemd。
服务使用现有本地 socket，不复制 API key，不开放公网端口，不重启既有 Codex 服务。

```sh
systemctl status andri-task-monitor.service
sudo systemctl stop andri-task-monitor.service
sudo systemctl disable andri-task-monitor.service
```

也可通过控制工具将 `enabled` 改成 `false` 暂停新检查和跟进，下一个扫描周期生效。
独立偏好优先于安装配置中同名的基础值；不要只改基础值就声称覆盖了已有偏好。
暂停不会取消已启动的 Codex 回合。`status.json` 记录服务心跳及连接状态，
`任务进度.md` 汇总最近的检查结果、证据边界和待决定事项；已撤回的问题不再显示成待决定。管家对话保留提问与用户交流。
主机重启恢复只要求此服务启用；检查模型仍依赖其 app-server 可用。桌面控制 socket 不在线时
会显示连接不可用并保留待处理事件，不把断线误判为任务已完成。

安装到已有管家对话时，空闲会话保留了初始工具清单，需要只对该管家做一次归档/取消归档，
再用 `configure_chat` 恢复，才能加载线程级 MCP。保留原 ID 与历史，并恢复置顶；不能中断
正在运行或等待用户的回合。后续服务重启直接恢复，其他任务不需要重载。

## 检查

```sh
python3 -B -m unittest discover -s codex/checks -p 'test_task_monitor*.py' -v
python3 -B codex/checks/check_task_monitor.py
python3 -B codex/checks/check_task_monitor_mobile_preferences.py
```

第二条使用独立 app-server 和本机假模型，通过真实协议检查「完成 → 判断 → 原任务继续 →
再次判断完成」、原生卡片/回复/重连、等待用户期间继续审查、内部线程隐藏、重启去重、忙碌队列保护，以及自然聊天调用真实控制命令、保存偏好、
下一周期生效和重启保留。不调用上游模型。测试线程归档，临时文件在 `codex/`。

手机偏好保存联调另启两个真实 app-server 和与生产同实现的手机入口，使用本机假模型调用真实
MCP 工具：桌面卸载后由手机入口接管同一会话，保存已确认规则，验证 `saved=true`、版本递增、
断线重连后再次调用读取工具仍保留；同时验证空闲已结束回合不能写入。报告位于
`codex/task-monitor/mobile-preferences-check-result.json`。这属于手机入口协议联调，不是安卓真机点击验收。
