# 开发与测试

## 构建基线

| 项目 | 版本 |
| --- | --- |
| JDK | 17 |
| Gradle | 8.13（wrapper，校验SHA-256） |
| AGP | 8.13.2 |
| Kotlin / Compose compiler | 2.3.21 |
| Compose BOM | 2025.10.01 |
| Media3 | 1.11.1 |
| compile / target SDK | 36 |
| min SDK | 29 |

## 结构

单app模块，按职责分包：

- `domain`：整数毫秒范围、顺序、历史、导出计划；不依赖Android媒体类型。
- `presentation`：EditorViewModel协调编辑与预览，导出时冻结项目。
- `data`：SAF来源、草稿、设置和本地缩略图缓存。
- `media/player`：统一播放时钟、定位节流和真实视频帧PTS。
- `media/export`：探测、Media3适配、逐片段输入进度、输出校验和安全写入。
- `ui`：Compose编辑器、侧栏、设置和导出界面。

每段为半开区间`[In, Out)`，所有行使用相同完整源坐标。行顺序就是输出顺序，不自动排序、去重或合并。新增范围为`[当前指针, 源时长)`，EOF拒绝创建。

快速模式不设置请求编码码率；导出后检查实际转换路径。精确模式对每段PCM应用样本预算，避免多段AAC尾差累积。SampleConsumer包装器显式转发全部Java默认方法。

先在私有目录生成并校验成品，再写入系统选择的新空文档。已有或未知非空目标不得覆盖/删除。编码任务不作进程死亡续传，已校验成品可以恢复保存。

## 检查

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
```

JVM测试覆盖范围、历史、时间映射和导出计划。设备测试覆盖播放器、编辑交互、草稿、输入进度、真实媒体输出、压缩载荷、边界帧、30片段AAC漂移、旋转、定位过滤、取消、安全写入、主题语言及侧栏模糊时机。

`app/src/androidTest/assets/`中的短视频为自动化使用的合成测试素材，包括颜色/帧计数、无声旋转、HDR拒绝和合成定位字段样本，不含用户视频。合成定位字段用于验证输出移除，不代表真实拍摄位置。

发布采用本机构建。GitHub Actions仅保留手动触发，可执行构建、JVM测试和lint；设备测试需额外连接设备/模拟器，不把CI编译成功等同于真实媒体兼容性验证。

## 继续验证

长视频/大文件/低空间、API29/30回退模糊与语言路径、更多硬件编码器和特殊时间戳/VFR输入需要扩充测试。既有小米/iQOO反馈仅覆盖实际用户测试的候选版本和素材。
