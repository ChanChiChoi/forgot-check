# 忘打卡

一个简单的 Android 示例应用。

## 功能

- 主界面中央有一个「按下去」按钮，点击后弹出对话框
- 右上角齿轮按钮可跳转到关于页面

## 技术栈

- 语言：Java
- 构建工具：Gradle 8.5 / Android Gradle Plugin 8.2.0
- 最低 SDK：21 (Android 5.0)
- 目标 SDK：34 (Android 14)
- 包名：`com.example.helloworld`

## 项目结构

```
app/src/main/
├── AndroidManifest.xml
├── java/com/example/helloworld/
│   ├── MainActivity.java      # 主界面，包含按钮交互逻辑
│   └── AboutActivity.java     # 关于页面
└── res/
    ├── layout/
    │   ├── activity_main.xml  # 主界面布局
    │   └── activity_about.xml # 关于页面布局
    └── values/
        ├── strings.xml
        └── colors.xml
```

## 构建

```bash
./gradlew assembleRelease
```

编译产物位于 `app/build/outputs/apk/release/`。

> **注意**：`build.gradle` 中的签名密钥配置（`my-release-key.jks`）不应提交到版本控制系统。
