#!/usr/bin/env bash
# ba-sync 数据同步服务 启动/停止/状态 脚本 (Ubuntu, java -jar)
# 用法: ./start.sh [start|stop|restart|status|log]
# 职责: 只从币安拉数据写入MySQL, 不做分析。需要能出网访问 fapi.binance.com。
set -euo pipefail

JAR='ba-sync-1.0.0.jar'   # mvn package -DskipTests 后在 target/ 下
LOG='sync.log'
# 优先 IPv4: EC2 等环境 IPv6 出网不通会导致币安请求卡死超时。
JAVA_OPTS='-Djava.net.preferIPv4Stack=true'

cd "$(dirname "$0")"

pid_of() { pgrep -f "java .*${JAR}" || true; }

start() {
  local pid; pid=$(pid_of)
  if [ -n "$pid" ]; then echo "已在运行 (PID $pid), 不重复启动。"; exit 0; fi
  local jarpath="$JAR"
  [ -f "$jarpath" ] || jarpath="target/$JAR"
  if [ ! -f "$jarpath" ]; then
    echo "找不到 $JAR — 先执行 mvn package -DskipTests。"; exit 1
  fi
  # 外部配置: jar 外的 ./config/application.yml 会逐项覆盖 jar 内打包的 application.yml
  # (optional: 文件不存在也不报错)。改配置后重启即可, 无需重新打包。
  nohup java $JAVA_OPTS -jar "$jarpath" \
    --spring.config.additional-location=optional:file:./config/ > "$LOG" 2>&1 &
  sleep 1
  echo "ba-sync 已启动 (PID $(pid_of)). 日志: $LOG  (用 ./start.sh log 跟踪)"
}

stop() {
  local pid; pid=$(pid_of)
  if [ -z "$pid" ]; then echo "未在运行。"; return; fi
  kill "$pid"; echo "已发送停止信号 (PID $pid)。"
  # 必须等进程真正退出: 否则 restart 会立刻进 start, 而 start 看到旧 PID 仍存活
  # 就判定"已在运行"直接跳过, 结果既没重启成功、服务也停着。
  for _ in $(seq 1 30); do
    kill -0 "$pid" 2>/dev/null || { echo "已停止。"; return; }
    sleep 1
  done
  echo "30s 未退出, 强制终止。"
  kill -9 "$pid" 2>/dev/null || true
  sleep 1
}

case "${1:-start}" in
  start)   start ;;
  stop)    stop ;;
  restart) stop; start ;;
  status)  pid=$(pid_of); [ -n "$pid" ] && echo "运行中 (PID $pid)" || echo "未运行" ;;
  log)     tail -f "$LOG" ;;
  *)       echo "用法: $0 [start|stop|restart|status|log]"; exit 1 ;;
esac
