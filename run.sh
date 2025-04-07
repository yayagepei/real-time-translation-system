#!/bin/bash

echo "正在启动实时翻译系统..."
echo ""

# 设置Java路径
if [ -z "$JAVA_HOME" ]; then
  echo "警告: 未设置JAVA_HOME环境变量, 尝试使用系统PATH中的java"
fi

# 添加执行权限
chmod +x ./mvnw

# 尝试启动应用
echo "正在编译并启动应用..."
echo ""

# 获取脚本所在目录
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR"

./mvnw clean spring-boot:run

if [ $? -ne 0 ]; then
  echo ""
  echo "启动失败！请确保已安装Java 17及以上版本。"
  echo ""
  read -p "按回车键继续..."
  exit 1
fi

read -p "按回车键继续..." 