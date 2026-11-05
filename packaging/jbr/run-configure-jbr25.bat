@echo off
call "E:\project\build\VC\Auxiliary\Build\vcvars64.bat"
set PATH=C:\msys64\usr\bin;%PATH%
cd /d "E:\project\jbr25-source"

bash configure ^
  --with-boot-jdk="/e/java" ^
  --with-toolchain-type=microsoft ^
  --with-debug-level=release ^
  --with-native-debug-symbols=none ^
  --with-jvm-features="compiler1,compiler2,g1gc,zgc,shenandoahgc,cds,link-time-opt,management,services,jvmti" ^
  --disable-warnings-as-errors
