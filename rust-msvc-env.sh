# 本机构建辅助：为非标准路径的 MSVC 工具链准备 cargo 构建环境。
# VS 装在 E:\project\build（不在 C:\Program Files），Windows SDK 是 10.0.26100.0。
# 用法（在仓库根执行）：source rust-msvc-env.sh && cd native/flick-player-engine && cargo build --release --offline --lib
# 详见 docs/dev/FLICK_ENGINE.md §6。
VC="E:/project/build/VC/Tools/MSVC/14.51.36231"
SDK="C:/Program Files (x86)/Windows Kits/10"
SDKV="10.0.26100.0"

# PATH 必须是 MSYS 风格（/e/...），bash 才会把它当成可搜索目录；
# 而 LIB / INCLUDE 是给 Windows 程序读的，必须是 E:\... 风格。
export PATH="/e/project/build/VC/Tools/MSVC/14.51.36231/bin/Hostx64/x64:/c/Program Files (x86)/Windows Kits/10/bin/$SDKV/x64:$PATH"

WVC="E:\\project\\build\\VC\\Tools\\MSVC\\14.51.36231"
WSDK="C:\\Program Files (x86)\\Windows Kits\\10"

export LIB="$WVC\\lib\\x64;$WSDK\\Lib\\$SDKV\\um\\x64;$WSDK\\Lib\\$SDKV\\ucrt\\x64"
export INCLUDE="$WVC\\include;$WSDK\\Include\\$SDKV\\ucrt;$WSDK\\Include\\$SDKV\\shared;$WSDK\\Include\\$SDKV\\um;$WSDK\\Include\\$SDKV\\winrt;$WSDK\\Include\\$SDKV\\cppwinrt"
