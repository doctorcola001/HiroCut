# 发布与签名

版本：`0.1.0`，versionCode：`8`。版本配置在`app/build.gradle.kts`。

## 两种本地产物

```bash
./gradlew :app:assembleDebug :app:assembleRelease
```

- `app-debug.apk`：用本机Android调试密钥签名，可安装；本项目已有测试机保持同一密钥时支持覆盖升级。其他机器和CI使用各自的调试密钥，可能无法覆盖安装。
- `app-release-unsigned.apk`：release构建，尚未签名，不能直接安装。

当前仓库不配置正式发布密钥，也不把debug密钥当作正式发布签名。正式可分发release需要所有者确定长期签名密钥，用Android Build Tools的`zipalign`/`apksigner`完成对齐、签名和验证。保管密钥和密码，不提交仓库。不同签名的包不能互相覆盖升级。

## 发布检查

1. 核对版本、CHANGELOG、README与已知限制。
2. JVM测试、lint、debug/release构建通过；在目标设备测试导入、裁切、排序、导出和保存。
3. 确认合并Manifest无INTERNET权限，仓库无本地配置、密钥、用户视频或机器日志。
4. 选定签名方式后验证APK签名，计算SHA-256，将APK和校验文件作为GitHub Release附件上传。
5. 使用`v0.1.0`标签；若上传的是测试APK，附件名和说明须明确`debug`。

本地历史APK和详细机器验证日志保留在忽略目录中，不随源码提交。未签名release产物不可标为已签名安装包。

## 许可证随包发布

本项目使用PolyForm Noncommercial 1.0.0。源码和再分发须保留LICENSE/NOTICE；第三方库原有许可见THIRD_PARTY_NOTICES.md。APK的assets/legal包含协议、版权通知和运行时依赖许可文本。发布前核对其与根目录文件一致。
