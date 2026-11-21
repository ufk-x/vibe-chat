# Vibe Chat 0.6 设计依据

这是一款 Android 原生应用。参考 Apple 的信息层级、分组列表和悬浮导航原则，保留 Android 的系统安装器、权限、键盘、相册与返回行为。

## 参考 skill 与文档

2026-11-07 从 GitHub 查找、读取，并用系统 skill-installer 安装以下 skill：

- [Apple HIG skill](https://github.com/ykswang/apple-hig-skill/blob/main/skills/apple-hig/SKILL.md)：按其中流程阅读 iOS 平台、Dark Mode、Color、Typography、Layout、Tab Bars 和 Lists and Tables 参考。
- [Mobile iOS Design skill](https://github.com/wshobson/agents/blob/main/plugins/ui-design/skills/mobile-ios-design/SKILL.md)：参考系统语义颜色、文字层级、安全区域、动态字号、原生控件与状态连续性。
- [Apple HIG · Dark Mode](https://developer.apple.com/design/human-interface-guidelines/dark-mode)、[Color](https://developer.apple.com/design/human-interface-guidelines/color)、[Tab bars](https://developer.apple.com/design/human-interface-guidelines/tab-bars)。
- [Android WindowInsetsController](https://developer.android.com/reference/android/view/WindowInsetsController)：系统栏图标的浅深色适配；Android 15 按系统 Insets 留出操作区域。

这些资料用于设计指导。没有复制 SwiftUI 实现，没有打包 Apple 的 SF 字体或 SF Symbols；使用 Android 系统字体与项目自行绘制的矢量图标。

## 本版落实

- 内容采用平实背景，大标题、主次文字和较细的分隔线；减少聊天页推广式色块。
- 四个固定 Tab 保留图标和标签，选中态同时用颜色与底色表示。浮动栏只承担导航；滚动区域实际留出它的高度，避免遮住录音或设置操作。
- “我”采用头像资料卡、带图标的分组设置；新增独立外观页，提供可点选的浅深色预览。
- 默认跟随系统，另可手动浅色/深色。这遵循用户要求与 Android 使用习惯；Apple skill 中“避免 app 独立外观设置”的建议不取代本项目需求。
- 语义颜色分别定义画布、内容表面、抬高表面、主文字、次文字、链接、按钮底色、淡强调色、分隔线。白色始终可用于实色按钮上的文字，不与内容表面混用。
- 深色采用分层炭黑，蓝色按钮与链接使用不同明度。QR 码保持白底深色码点，头像保留原始图像内容。
- 常用操作至少 48 dp 触摸高度，正文以 sp 随系统字号缩放；Tab 栏高度随标签高度计算，预留区域随实际布局变化。
- 外观偏好仅保存在本机，退出账号和覆盖更新后保留。系统换色不重建 Activity；聊天草稿、输入焦点和软键盘保持。正在按住录音时延后换色，收到抬手事件后再刷新视图。
- 浮动导航使用低透明度渐变和描边以控制渲染开销，并非 Apple Liquid Glass 的原生模糊实现。

## 0.6.1 动效与提示

继续应用 Apple HIG skill 的 [Motion](https://developer.apple.com/design/human-interface-guidelines/motion) 指导：动效用于解释当前状态，限制在正在播放的一条语音或当前对讲波形中。无声或停止时不持续晃动，并尊重 Android 全局动画关闭设置。播放图标/停止图标和读屏文本提供不依赖动画的反馈。

更新红点放在「我」的图标右上角和更新设置行尾部，不改变 Tab 栏高度，不覆盖图标主体。在设置行同时给出具体新版本号，读屏也会提示有新版本。更新公告独立刷新，避免重建聊天输入框或打断录音。

## 0.6.2 应用图标（2026-11-08）

按 Apple HIG skill 的 App Icons 页重新设计「气泡里的声音」：完整的白色对话气泡，三个圆端负形声波；不加名称、装饰边框或细小文字。蓝色轻渐变强调前景，未烘焙高光、投影、玻璃纹理。主体与背景分层，留白形成声波，单色模式也保留同一轮廓。

- 原创 SVG 母版 `design/brand/vibe-chat.svg`（1024 方形、未预先圆角）及前/背景 108 单位图层。
- Android 8+ 自适应图标使用 108 dp 图层，主体落在中心 66 dp 安全区；外圈保留给系统裁切和效果，不把圆角画进资源。
- Android 13+ monochrome 使用相同透明负形图层，桌面主题着色后仍有清晰波形。此能力取决于桌面支持及系统主题图标开关，不与 App 内浅深色偏好绑定。
- 通知栏是无底色的纯色剪影，按 24 dp 通知尺寸放大到约 20 dp 内容区；启动页和系统安装界面继承应用图标。
- 不增加别名 Activity 或主动切换桌面组件，覆盖更新保持原入口。

依据：[Apple App Icons](https://developer.apple.com/design/human-interface-guidelines/app-icons)、[Android Adaptive Icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。这里借鉴 Apple 的设计原则，资源和系统行为使用 Android 原生实现。

## 0.6.4 二级界面与入口语义（2026-11-08）

沿用 Apple HIG skill，阅读 [Action Sheets](https://developer.apple.com/design/human-interface-guidelines/action-sheets)、[Sheets](https://developer.apple.com/design/human-interface-guidelines/sheets)、[Alerts](https://developer.apple.com/design/human-interface-guidelines/alerts) 和 [Menus](https://developer.apple.com/design/human-interface-guidelines/menus)。

- 扫码方式、头像来源、发起对话和会话操作属于用户主动发起的选择，使用底部操作面板，含图标/短标签、必要说明与独立取消入口。普通尺寸下短操作列表不需要滚动；大字号、横屏或较长好友列表时允许滚动，取消按钮保持可见。
- 备注、昵称、密码、邀请输入采用底部表单，取消在左、保存/继续在右。键盘出现后整个面板避让，输入行以内容高度布局。
- 信息和二维码采用内容面板，二维码白底深色码点不随深色模式反转；复制邀请是主要操作，重置单列并提示旧码失效。关闭不被挤进三个并排按钮。
- 退出、解散、重置等确认采用居中圆角卡片，最多三项，具体动作代替含糊的「确认」。大字号按钮纵向排列；破坏性操作用文字和红色共同标识。
- 一次仅显示一个应用面板，进入下一步先关闭前一个；支持系统返回、明确取消/关闭以及空白遮罩取消。无输入内容的操作面板标题区可下滑关闭；固定高度，不画暗示可拉伸的假把手。
- Android 原生 Dialog/View 实现共同容器，最大宽度与 Insets 约束防止横屏溢出；沿用语义颜色和原生 EditText。切换系统主题重绘已打开面板，复用输入框以保留内容与焦点；Activity 销毁时清理窗口。
- 「扫一扫」为取景框图形，只表示扫描行为；「我的二维码」使用三定位点二维码图形，资料卡上补充可见「二维码」文字。好友页移除重复卡片，联系人与好友申请成为主体。


## 0.6.5 双方头像与动效（2026-11-08）

参考 [Apple HIG Motion](https://developer.apple.com/design/human-interface-guidelines/motion) 的短暂、可打断、随手势与尊重减少动态效果原则，采用 Android 原生属性动画。下列时长和曲线为本项目取值，不是 Apple 指定参数。

- 双方头像同为 38 dp；自己在消息右侧，对方在左侧，间距 8 dp。文字、语音、转文字与时间对齐于头像旁的内容列；发送中/失败重试保持同样布局。自身头像读取当前资料版本，支持缓存刷新。
- Tab 背景 360 ms 阻尼回弹；背景高度随实际标签内容测量，保持导航栏紧凑并兼容大字号。点击立即切换页面，260 ms 短距离水平缓动表示切换或进入/返回聊天。
- 按压缩至 97%，100 ms 压下、280 ms 回位；使用 StateListAnimator，不截获按钮触摸。录音控件在原触摸回调里同步更新 pressed，录音开始、结束与取消仍立即调用。
- 底部面板 380 ms 小幅阻尼滑入，180 ms 向下退出；确认卡片 280 ms 缩放/淡入。遮罩单独变化，覆盖安全区域。面板可在打开过程中取消，关闭动作设单次保护，快速连点不重复触发业务操作；下一面板等上一面板关闭再呈现。
- 无输入内容的面板标题跟随下拉；不足阈值时 320 ms 回位，超过阈值关闭。输入表单不增加拖动关闭，以免误丢输入。
- ValueAnimator.areAnimatorsEnabled() 为 false 时导航、弹窗和关闭直接完成；StateListAnimator 自动遵循系统时长。列表数据刷新、主题重绘不重复整页过渡；无新增持续运行的装饰动画。


## 0.6.6 会话性能与整页过渡（2026-11-08）

继续应用 Apple HIG Motion 的连续、短暂、可打断原则。首屏采用 Android 原生 ListView / BaseAdapter，仅创建可见消息行、复用容器，通过消息 ID、内容和显示身份决定是否重绑。未变化的状态刷新不调用整批重绘，不改语音播放或录音时序。

- 整页 PageHost 同时保留前后页，进入时会话从右侧推入、原页轻微左移；返回按相反方向退出。标题、列表和输入栏作为整体，不先跳换再只移动内容。300 ms 缓动，不在整页上加弹跳。
- 离开页面有触摸遮挡，防止动画间隙点击旧列表；结束时释放旧页，中断时立即完成清理再执行下一次导航，不锁住返回。新页面显式重新申请系统栏、缺口和键盘 Insets。
- 当前会话内仅维护可见消息的播放指示；回收时旧波形停止，重新可见时恢复准确播放状态。消息行始终保留双方头像和转文字区域。
- 每个登录会话在内存缓存最近 6 段聊天（每段保留服务返回的最近 50 条，与既有接口限制一致），不新写聊天明文文件；账号退出、切换、会话权限撤销时清理。新会话显示加载状态，失败可点击重试，有缓存时仍可查看。
- 记录请求与一般 API 串行队列分离，重复请求合并，响应校验账号代次和会话请求序号；不会把较晚返回的 A 会话内容显示到 B 会话。
- 数据刷新根据稳定消息 ID 保持当前阅读位置。用户已在底部时跟随新内容；上翻阅读时保存首个可见消息与像素偏移，不因转写或已读状态变化跳回底部。


## 0.6.7 悬浮对讲面板（2026-11-10）

继续应用 apple-hig 的 Materials、Buttons、Gestures 与 Motion 原则，使用 Android 原生 View / TYPE_APPLICATION_OVERLAY 实现；不复制 Apple 资源、不依赖 iOS 框架。高不透明度渐变和细描边模拟材质层级，没有使用实时背景模糊。

- 展开面板宽 264 dp（含阴影留白），26 dp 圆角；「正在回复」和单行目标名在上，蓝色 56 dp 录音区是主要操作，下方提示可拖动。收起与隐藏使用 32 dp 圆形底、16 dp 线性图标、48 dp 实际触控范围。
- 收起形态为 56 dp 圆形，实际窗口宽 68 dp（含留白），轻点展开；收起后同步缩小 WindowManager 窗口，不用透明大窗口挡住后方应用。最近回复对象继续通过无障碍描述提供。
- 整个 DragSurface 识别拖动；录音区 DOWN 开始后整段手势交给既有按住录音处理，保留上滑取消。其他区域超过系统 touch slop 才拦截，系统向子按钮发送 CANCEL，防止移动时触发收起或隐藏。
- 松手以 220 ms 缓动靠近左右边缘，新触摸可打断吸附。展开/收起有短暂缩放和淡入；关闭系统动画时直接呈现最终状态。位置与形态本机保存。
- 外观跟随本 App 的浅/深/系统偏好；服务直接观察 App 更新及系统配置变化，不依赖打开 Activity。字号、系统栏和横竖屏改变后重新测量并约束到安全区域。长名称省略并提供完整 tooltip/无障碍标签。
- 录音期间禁用收起/隐藏；配置改变先取消录音，录音结束和手势结束后再重排，避免替换正在处理录音的视图。隐藏关闭窗口和监听，不结束后台对讲。


## 0.6.8 紧凑对讲浮条（2026-11-10）

本轮实际联网查阅的参考：
- [Apple Music MiniPlayer](https://support.apple.com/en-gb/guide/iphone/iph676daac9b/ios)：紧凑播放控制的结构；图片检索用于观察短横条、主要操作与单行信息的关系。
- [Apple AssistiveTouch](https://support.apple.com/en-ie/111794)：小型常驻按钮、点击展开与拖到边缘的交互。
- [Floating Bubble View](https://github.com/dofire/Floating-Bubble-View)：查阅 README 的展开/收起、边缘吸附与关闭目标示例。借鉴交互，不引入库或复制源码/美术资源。
- 继续应用 apple-hig 的 Buttons / Gestures / Motion 原则，以 Android 原生 View 实现，不声称实现了 iOS Liquid Glass 背景模糊。

常驻展开面板改为 200 × 56 dp 横向胶囊，窗口含阴影留白为 208 × 64 dp（默认字体）；左侧 40 dp 麦克风图形、48 dp 触控范围，中间回复对象和简短操作提示，右侧收起按钮。移除上一版「正在回复」、底部拖动说明和常驻关闭按钮。收起图形直径 40 dp，保留 48 dp 触控范围，窗口 56 × 56 dp。

拖动开始才出现屏幕底部关闭目标，靠近时变红并显示「松手隐藏」，松手隐藏窗口、保持对讲。拖动取消只移除目标；录音手势不显示关闭目标，不混入移动操作。无障碍 ACTION_DISMISS 与 App 内的悬浮开关仍可隐藏窗口。保留贴边、位置与形态记忆、主题跟随、录音取消和减少动画设置。
