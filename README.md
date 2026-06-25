# 音乐小部件 (Music Widget)

个人自用软件，完全由豆包办公模式AI开发的一款具有半透明质感的 Android 桌面音乐小部件，支持根据专辑封面自动提取主色调变色。

## ✨ 功能特性

- 🎵 **实时音乐信息**：通过 MediaSession API 读取系统正在播放的音乐（歌名、歌手、专辑封面）
- 🎨 **专辑封面取色**：自动提取专辑封面主色调，小部件背景随之变色
- 🎛️ **完整播放控制**：支持播放/暂停、上一首、下一首控制
- 🖼️ **点击专辑打开应用**：点击专辑封面直接打开当前播放的音乐应用
- 📱 **显示播放应用名**：播放状态显示「正在播放 · 应用名」或「已暂停 · 应用名」
- 🌈 **图标同步变色**：播放器按钮颜色与文字颜色同步变化
- 📐 **自适应尺寸**：支持 4x2 默认规格，可自由调整大小
- 🔄 **多应用兼容**：支持所有使用 MediaSession 的音乐应用
- ⚙️ **设置界面**：Material Design 3 风格，支持透明度、染色深度调节

## 📋 系统要求

- **最低 Android 版本**：API 31 (Android 12)
- **目标 Android 版本**：API 34 (Android 14)
- **需要权限**：通知访问权限（用于读取 MediaSession）

## 🚀 快速开始

### 1. 导入项目

使用 Android Studio 打开项目文件夹，等待 Gradle 同步完成。

### 2. 编译运行

连接 Android 设备或启动模拟器，点击 Run 按钮编译安装。

## 📝 使用说明

### 首次使用

1. 安装并打开应用
2. 在设置界面授予通知访问权限
3. 返回桌面，长按空白处添加小部件
4. 选择「音乐小部件」添加到桌面

### 支持的音乐应用

所有实现了 MediaSession API 的音乐应用都可以被检测到，包括但不限于：
- 网易云音乐
- QQ 音乐
- Spotify
- Apple Music
- 酷狗音乐
- 酷我音乐
- 哔哩哔哩（视频播放也能检测到）
- 系统自带音乐播放器

## 🏗️ 项目结构

```
GlassMusicWidget/
├── app/
│   ├── src/main/
│   │   ├── java/com/glassmusic/widget/
│   │   │   ├── MusicWidgetProvider.kt    # 小部件核心类（更新UI、处理事件）
│   │   │   ├── MusicMonitorService.kt    # 音乐监听服务（监听MediaSession变化）
│   │   │   └── MainActivity.kt           # 主Activity（设置界面、权限管理）
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   ├── widget_music.xml      # 小部件布局
│   │   │   │   └── activity_main.xml     # 设置界面布局（M3风格）
│   │   │   ├── drawable/                 # PNG图标和透明背景资源
│   │   │   ├── mipmap-xxhdpi/            # 应用图标
│   │   │   ├── values/                   # 字符串、颜色、主题
│   │   │   └── xml/                      # 小部件配置
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── build.gradle
├── settings.gradle
└── gradle.properties
```

## 🎨 技术实现

### 半透明背景效果

- 使用 PNG 图片作为半透明背景（三层叠加 HyperLight 参数）
- 通过 ImageView 的 `setColorFilter` 实现背景颜色动态变化
- 8 倍超采样抗锯齿圆角，边缘清晰无虚化

### 专辑封面取色

- 使用 AndroidX Palette API 提取专辑封面主色调
- 优先提取 Vibrant（有活力）颜色，fallback 到 Dominant（主色）
- 调整颜色透明度后应用到背景
- 文字和图标颜色根据背景亮度自动切换（黑/白）

### 音乐监听

- 通过 NotificationListenerService 获取 MediaSession 访问权限
- 监听活动的 MediaSession 变化
- 实时获取播放状态、歌曲信息、专辑封面
- 支持多音乐应用自动切换

### 小部件控制

- 通过 PendingIntent 实现按钮点击事件
- 直接调用 MediaController 的 TransportControls 进行播放控制
- 无需启动 Activity 即可完成控制操作
- 点击专辑封面打开当前播放的音乐应用

### 非正方形封面圆角处理

- 先将 Bitmap 居中裁剪成正方形（centerCrop 效果）
- 再给正方形 Bitmap 添加圆角
- 确保各种比例的封面都能正确显示圆角

### 设置界面

- Material Design 3 风格设计
- SharedPreferences 存储配置
- 支持调节：玻璃透明度、染色深度、自动取色开关
- 实时预览，应用后立即生效

## 🔧 核心代码说明

### MusicWidgetProvider.kt（小部件核心）

主要函数：
- `onUpdate()`：小部件更新时调用
- `updateAppWidget()`：更新单个小部件的所有内容
- `updateMusicInfo()`：更新歌曲信息和专辑封面
- `updatePlaybackState()`：更新播放状态和按钮图标
- `setTextColors()`：根据背景亮度设置文字和图标颜色
- `getRoundedBitmap()`：给专辑封面添加圆角（先裁剪成正方形）
- `setupControlIntents()`：设置控制按钮的点击事件
- `setupAlbumArtIntent()`：设置专辑封面的点击事件（打开音乐应用）
- `getCurrentAppName()`：获取当前播放应用的显示名称

### MusicMonitorService.kt（音乐监听服务）

- 继承自 NotificationListenerService
- 监听 MediaSession 变化
- 音乐信息变化时发送广播，通知小部件更新

### MainActivity.kt（设置界面）

- 权限检查和跳转
- SharedPreferences 配置读写
- 滑块调节透明度和染色深度
- 开关控制自动取色
- 应用设置后立即更新所有小部件

## ⚠️ 兼容性注意事项

RemoteViews 存在一些兼容性限制，开发中需要注意：

1. **矢量图标（Vector Drawable）** 会导致小部件加载失败 → 使用 PNG 位图图标
2. **Shape Drawable** 作为背景会导致加载失败 → 使用 PNG 图片代替
3. **单独的空 View** 在 FrameLayout 中可能导致失败 → 使用 ImageView 代替
4. **setColorFilter** 在 ImageView 中可以正常使用，用于实现动态变色

## 📄 许可证

MIT License

效果图：
<img width="1200" height="2670" alt="Screenshot_2026-06-25-13-14-17-823_com glassmusic widget" src="https://github.com/user-attachments/assets/fe0ae388-a5b1-4be0-bd95-f7e03c4ddd84" />
<img width="1200" height="655" alt="Screenshot_2026-06-25-13-14-27-893_com miui home-edit" src="https://github.com/user-attachments/assets/32cf196d-36ba-4c70-a8b7-8ec8dd9caf1f" />
<img width="1200" height="634" alt="Screenshot_2026-06-25-13-14-48-030_com miui home-edit" src="https://github.com/user-attachments/assets/2b9050fe-fde2-4a00-87fb-7dfb8590b5f4" />
<img width="1200" height="617" alt="Screenshot_2026-06-25-13-15-06-129_com miui home-edit" src="https://github.com/user-attachments/assets/cdbaceba-ea69-4824-8a8b-9a48df972ffc" />



