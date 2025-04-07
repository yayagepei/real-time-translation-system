@echo off
echo 正在启动实时翻译系统...
echo.

REM 设置Java路径
set JAVA_HOME=%JAVA_HOME%
if "%JAVA_HOME%" == "" (
  echo 警告: 未设置JAVA_HOME环境变量, 尝试使用系统PATH中的java
)

REM 尝试启动应用
echo 正在编译并启动应用...
echo.

cd /d "%~dp0"
call mvnw.cmd clean spring-boot:run
if %ERRORLEVEL% NEQ 0 (
  echo.
  echo 启动失败！请确保已安装Java 17及以上版本。
  echo.
  pause
  exit /b 1
)

pause 