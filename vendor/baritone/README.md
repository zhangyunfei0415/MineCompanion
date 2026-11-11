# Baritone 1.10.1 for Forge 1.20.1

本目录保留官方发行的 API Forge JAR 与 v1.10.1 对应源码归档，采用 LGPL-3.0-or-later。当前内嵌二进制保持原样，伙伴的控制与策略适配位于 `BaritoneNavigation.java`。

- 官方发行包为 https://github.com/cabaletta/baritone/releases/tag/v1.10.1 。
- 源码归档来自 https://github.com/cabaletta/baritone/archive/refs/tags/v1.10.1.zip 。
- `LICENSE` 为该源码归档的许可证，`GPL-3.0.txt` 为其引用的 GPL 全文。

伙伴的 `processResources` 将原 JAR 放入 `META-INF/jarjar`，Forge 使用同目录 metadata 发现模块。该二进制可在重新构建伙伴时替换为相容的修改版本。源码归档中的 Gradle wrapper、Forge 子工程和构建脚本保留原样，可用于单独构建上游模块。伙伴的完整源码包包含本目录，供复现和修改。

完整伙伴编译使用已有 `build-local.ps1`。本目录的官方 JAR目前通过原发行构建取得，伙伴构建过程嵌入该产物。Forge 实际加载、Mixin 兼容和与外部 Baritone 同装的选择行为需要游戏内验收。
