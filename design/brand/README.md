# Vibe Chat · 气泡里的声音

原创标记：圆角对话气泡 + 三个负形圆端声波。SVG 为项目原创矢量，不包含字体或外部图标库。

- `vibe-chat.svg`：1024×1024 方形母版，未预裁圆角。
- `foreground.svg` / `background.svg`：108×108 图层，与 Android 资源坐标一致；单色主题复用 foreground 的透明负形。
- Android 实际资源：`app/src/main/res/drawable/ic_launcher_*`、`mipmap-anydpi-v26` 和 `mipmap-anydpi-v33`。

背景上色 #3294FF → #1461E8，前景白色。桌面负责最终外形；预览中的圆形/圆角仅演示系统遮罩。源 SVG 的 viewBox 18 18 72 72 对应默认可见区域，108 图层外缘用于系统视差。
