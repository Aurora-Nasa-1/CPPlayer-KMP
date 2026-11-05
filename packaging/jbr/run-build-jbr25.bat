@echo off
call "E:\project\build\VC\Auxiliary\Build\vcvars64.bat"
set PATH=C:\msys64\usr\bin;%PATH%
cd /d "E:\project\jbr25-source"

make clean
make images
