# app/graalvm/substitutions — ICU4J GraalVM Substitution

此目录包含针对 ICU4J `BreakIterator` 与 `ULocale$JDKLocaleHelper` 的 GraalVM Substitution 预编译 JAR 及源码。

## 背景与作用
1. **`Target_BreakIterator`** (`package com.ibm.icu.text`):
   将 `BreakIterator.getShim()` 内的 `Class.forName("com.ibm.icu.text.BreakIteratorFactory")` 动态反射加载替换为静态直接实例化 `new BreakIteratorFactory()`。彻底消灭 `BreakIteratorFactory` 的反射注册依赖。
2. **`Target_JDKLocaleHelper`** (`package com.ibm.icu.util`):
   将 `ULocale$JDKLocaleHelper` 的 `getDefault(Category)` 与 `setDefault(Category, Locale)` 改为直接调用 JDK 7+ 的 `Locale.getDefault(Locale.Category)` 原生直调 API，固定 `hasLocaleCategories = true`，彻底消灭 `Method.invoke` 反射依赖。

## 为什么独立打包为 JAR
1. 替身类包含 GraalVM 专有构建注解 `com.oracle.svm.core.annotate.*`。独立打包为 JAR 并以 `desktopRuntimeOnly` 挂载，可确保普通 JVM（JBR 21）日常开发、编译（`:app:compileKotlinDesktop`）与测试完全不依赖 GraalVM SDK 内部 API，环境零侵入。
2. Windows 生产 Native Image 构建时，Nucleus 会将 runtimeClasspath 打入镜像并自动识别类路径上的 `@TargetClass` 替身类，实现原生级别的 AOT 直连去反射。
