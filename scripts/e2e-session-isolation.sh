#!/usr/bin/env bash
#
# 跨协议「相同设备号互不覆盖」端到端联调脚本。
#
# 目标：在同一网关实例上，用两个不同协议监听端口（SOS-C02 / demo-text）接入<b>相同 deviceId</b> 的两台设备，
#       验证会话以 (protocolType, deviceId) 复合键隔离：在线列表按协议可分别聚焦查询、聚合查询均命中两条、
#       且对同一 deviceId 的两个协议会话分别下行的下行均能送达。
#
# 用法（脚本会从仓库根构建产物并按需拉起网关与两个演示客户端，结束后自动清理）：
#   ./scripts/e2e-session-isolation.sh
# 可覆盖的环境变量（默认已避开 8080/9000/9001，防止与本地已在跑的网关冲突）：
#   ADMIN_PORT  管理端口           默认 8085
#   SOS_PORT    SOS-C02 监听端口    默认 9300
#   TEXT_PORT   demo-text 监听端口  默认 9301
#   DEVICE_ID   两协议共用的设备号   默认 869975034441082
#
set -euo pipefail

ADMIN_PORT="${ADMIN_PORT:-8085}"
SOS_PORT="${SOS_PORT:-9300}"
TEXT_PORT="${TEXT_PORT:-9301}"
DEVICE_ID="${DEVICE_ID:-869975034441082}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$REPO_ROOT/iotbridge-starter/target/iotbridge.jar"
WORK_DIR="$(mktemp -d)"
GW_LOG="$WORK_DIR/gateway.log"
CFG_YML="$WORK_DIR/listeners.yml"

echo "== iotbridge 跨协议会话隔离端到端联调 =="
echo "管理端口=$ADMIN_PORT SOS端口=$SOS_PORT 文本端口=$TEXT_PORT 共用设备号=$DEVICE_ID"
echo "临时工作目录=$WORK_DIR"

cleanup() {
    echo "== 清理进程与临时文件 =="
    for pid_var in GW_PID SOS_PID TEXT_PID; do
        local pid="${!pid_var:-}"
        if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null || true
        fi
    done
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT

# 1) 监听配置：以独立配置源声明两个监听（各自绑定协议与切帧方式），规避 Spring 集合跨源整体替换问题
cat > "$CFG_YML" <<YML
iotbridge:
  # 使用专用的下行交换机/队列/路由键，避免与本机其他常驻网关（如 IDE 启动）争抢同一队列的投递
  mq:
    downlink-exchange: iotbridge.downlink.e2e.exchange
    downlink-queue: iotbridge.downlink.e2e
    downlink-routing-key: downlink.e2e
  netty:
    listeners:
      - name: sos
        port: ${SOS_PORT}
        protocol-type: sos-c02
        frame-mode: LENGTH_FIELD
        max-frame-length: 1024
        length-field-offset: 9
        length-field-length: 2
        length-adjustment: 5
        initial-bytes-to-strip: 0
      - name: demo-text
        port: ${TEXT_PORT}
        protocol-type: demo-text
        frame-mode: DELIMITER
        max-frame-length: 65536
YML

# 2) 构建产物（缺失才打包，跳过测试加速）
if [[ ! -f "$JAR" ]]; then
    echo "== 未找到 $JAR，开始构建（跳过测试） =="
    (cd "$REPO_ROOT" && mvn -q -DskipTests package)
fi

# 3) 启动网关（后台，日志落盘）
echo "== 启动网关 =="
java -jar "$JAR" \
    --server.port="$ADMIN_PORT" \
    --logging.level.com.iotbridge.bridge.core=DEBUG \
    --spring.config.additional-location="file:$CFG_YML" > "$GW_LOG" 2>&1 &
GW_PID=$!

wait_for_log() {
    local pattern="$1" timeout="${2:-60}" waited=0
    while ! grep -q "$pattern" "$GW_LOG" 2>/dev/null; do
        if ! kill -0 "$GW_PID" 2>/dev/null; then
            echo "!! 网关进程提前退出，日志尾部：" ; tail -40 "$GW_LOG"; exit 1
        fi
        if (( waited >= timeout )); then
            echo "!! 等待超时：日志中未出现 [$pattern]"; tail -40 "$GW_LOG"; exit 1
        fi
        sleep 1; waited=$((waited + 1))
    done
}

wait_for_log "Netty 监听已启动 name=sos" 90
wait_for_log "Netty 监听已启动 name=demo-text" 30
echo "   网关已就绪，两监听分别绑定 sos-c02 / demo-text"

# 4) 以相同 deviceId 分别接入两台设备（不同协议端口）。exec:java 在 Maven 进程内运行，kill 该进程即停客户端
echo "== 启动 SOS 设备客户端（protocol=sos-c02） =="
(cd "$REPO_ROOT" && mvn -q -pl iotbridge-starter exec:java \
    -Dexec.mainClass=com.iotbridge.demo.SosDemoClient \
    -Dexec.classpathScope=test \
    -Dexec.args="$DEVICE_ID 127.0.0.1 $SOS_PORT") > "$WORK_DIR/sos-client.log" 2>&1 &
SOS_PID=$!

echo "== 启动文本设备客户端（protocol=demo-text，相同设备号） =="
(cd "$REPO_ROOT" && mvn -q -pl iotbridge-starter exec:java \
    -Dexec.mainClass=com.iotbridge.demo.DemoDeviceClient \
    -Dexec.classpathScope=test \
    -Dexec.args="$DEVICE_ID 127.0.0.1 $TEXT_PORT") > "$WORK_DIR/text-client.log" 2>&1 &
TEXT_PID=$!

wait_for_log "设备上线 protocol=sos-c02 deviceId=$DEVICE_ID" 90
wait_for_log "设备上线 protocol=demo-text deviceId=$DEVICE_ID" 60
echo "   两台设备均已上线（相同 deviceId、不同 protocol）"

# 5) 在线查询断言
BASE="http://localhost:$ADMIN_PORT/api/v1/devices"
echo "== 在线查询验证 =="
ALL_ONLINE="$(curl -fsS "$BASE/online")"
SOS_ONLINE="$(curl -fsS "$BASE/online?protocol=sos-c02")"
TEXT_ONLINE="$(curl -fsS "$BASE/online?protocol=demo-text")"
AGG="$(curl -fsS "$BASE/$DEVICE_ID/online")"
SOS_PRECISE="$(curl -fsS "$BASE/$DEVICE_ID/online?protocol=sos-c02")"
TEXT_PRECISE="$(curl -fsS "$BASE/$DEVICE_ID/online?protocol=demo-text")"

echo "   全量在线         : $ALL_ONLINE"
echo "   仅 sos-c02       : $SOS_ONLINE"
echo "   仅 demo-text     : $TEXT_ONLINE"
echo "   聚合在线(任意协议): $AGG"
echo "   精确 sos-c02     : $SOS_PRECISE"
echo "   精确 demo-text   : $TEXT_PRECISE"

count_device_in() {
    # 统计给定 JSON 结果里目标 deviceId 出现的条目数（协议过滤由服务端完成，此处计数即可）
    local json="$1"
    printf '%s\n' "$json" | grep -o "\"deviceId\":\"$DEVICE_ID\"" | wc -l | tr -d '[:space:]'
}

fail=0
assert_eq() {
    local label="$1" actual="$2" expected="$3"
    if [[ "$actual" == "$expected" ]]; then
        echo "   [OK] $label 期望=$expected 实际=$actual"
    else
        echo "   [FAIL] $label 期望=$expected 实际=$actual"; fail=1
    fi
}

assert_eq "全量在线含两条同号会话" "$(count_device_in "$ALL_ONLINE")" "2"
assert_eq "sos-c02 过滤命中同号一条" "$(count_device_in "$SOS_ONLINE")" "1"
assert_eq "demo-text 过滤命中同号一条" "$(count_device_in "$TEXT_ONLINE")" "1"
assert_eq "聚合在线判定" "$(grep -o '"data":true' <<<"$AGG" | head -1)" '"data":true'
assert_eq "精确 sos-c02 在线" "$(grep -o '"data":true' <<<"$SOS_PRECISE" | head -1)" '"data":true'
assert_eq "精确 demo-text 在线" "$(grep -o '"data":true' <<<"$TEXT_PRECISE" | head -1)" '"data":true'

# 6) 对同一 deviceId 的两个协议会话分别下行，验证按 (protocol,deviceId) 精确寻址、互不串扰
echo "== 分别下行两个协议会话 =="
curl -fsS -XPOST "$BASE/$DEVICE_ID/commands" -H 'Content-Type: application/json' \
    -d '{"protocolType":"sos-c02","command":"timeCalib","params":{"vendor":"0001"}}' > "$WORK_DIR/dl-sos.json" || true
curl -fsS -XPOST "$BASE/$DEVICE_ID/commands" -H 'Content-Type: application/json' \
    -d '{"protocolType":"demo-text","command":"ping","params":{"echo":"hello"}}' > "$WORK_DIR/dl-text.json" || true
echo "   下行(sos-c02) 返回 : $(cat "$WORK_DIR/dl-sos.json")"
echo "   下行(demo-text) 返回: $(cat "$WORK_DIR/dl-text.json")"

SOS_CLIENT_LOG="$WORK_DIR/sos-client.log"
TEXT_CLIENT_LOG="$WORK_DIR/text-client.log"

# 轮询某文件直至出现指定内容（超时返回 1，不中断脚本）
log_appears() {
    local file="$1" pattern="$2" timeout="${3:-20}" waited=0
    while ! grep -q "$pattern" "$file" 2>/dev/null; do
        if (( waited >= timeout )); then return 1; fi
        sleep 1; waited=$((waited + 1))
    done
    return 0
}
# 断言：文件含指定内容，否则计入失败
assert_log() {
    local label="$1" file="$2" pattern="$3"
    if log_appears "$file" "$pattern" 20; then
        echo "   [OK] ${label}"
    else
        echo "   [FAIL] ${label} ，未在 ${file} 观测到：${pattern}"; fail=1
    fi
}

# 分发层：两条下行均按各自的具象协议送达（DEBUG 级日志）
assert_log "sos-c02 会话下行送达" "$GW_LOG" "下行已送达 protocol=sos-c02 deviceId=$DEVICE_ID"
assert_log "demo-text 会话下行送达" "$GW_LOG" "下行已送达 protocol=demo-text deviceId=$DEVICE_ID"
# 设备侧：两台同号设备各自收到自己的下行帧（真正端到端证据）
assert_log "sos 设备客户端收到下行帧" "$SOS_CLIENT_LOG" "收到下行帧"
assert_log "demo-text 设备客户端收到下行" "$TEXT_CLIENT_LOG" "收到下行"
grep "下行已送达" "$GW_LOG" | tail -5 | sed 's/^/   日志: /' || true

if (( fail == 0 )); then
    echo "== 结果：跨协议相同设备号会话隔离验证通过 =="
else
    echo "== 结果：存在断言失败，请查看网关日志 ${GW_LOG} （trap 会清理，如需保留请注释 cleanup） =="
    exit 1
fi
