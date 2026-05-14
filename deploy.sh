#!/bin/bash
# =============================================================================
# GateFlow Tracker Service 部署脚本
# 用途：打包 tracker-service 并上传到远程服务器
# =============================================================================

set -e  # 遇到错误立即退出

# 配置
SERVER_IP="47.254.15.184"
SERVER_USER="hicooper"
SERVER_PATH="/home/hicooper/backend/tracker-service"
JAR_NAME="tracker-service-1.0.0-SNAPSHOT.jar"
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查前置条件
check_prerequisites() {
    log_info "检查前置条件..."

    # 检查 Maven
    if ! command -v mvn &> /dev/null; then
        log_error "Maven 未安装，请先安装 Maven"
        exit 1
    fi
    log_info "Maven 版本: $(mvn -version | head -n 1)"

    # 检查 SSH 连接
    if ! ssh -o ConnectTimeout=5 ${SERVER_USER}@${SERVER_IP} "echo 'SSH OK'" &> /dev/null; then
        log_error "无法连接到 ${SERVER_USER}@${SERVER_IP}，请检查 SSH 配置"
        exit 1
    fi
    log_info "SSH 连接正常"

    # 检查 scp
    if ! command -v scp &> /dev/null; then
        log_error "scp 未安装"
        exit 1
    fi
}

# 清理并打包
build_package() {
    log_info "开始构建项目..."

    cd "${PROJECT_DIR}"

    # 清理并打包（跳过测试）
    log_info "执行 mvn clean package -DskipTests..."
    mvn clean package -DskipTests -q

    # 检查 JAR 文件
    JAR_PATH="${PROJECT_DIR}/target/${JAR_NAME}"
    if [ ! -f "${JAR_PATH}" ]; then
        log_error "构建失败，未找到 JAR 文件: ${JAR_PATH}"
        exit 1
    fi

    JAR_SIZE=$(du -h "${JAR_PATH}" | cut -f1)
    log_info "构建成功，JAR 文件大小: ${JAR_SIZE}"
}

# 创建远程目录结构
create_remote_dirs() {
    log_info "创建远程服务器目录..."

    ssh ${SERVER_USER}@${SERVER_IP} << 'EOF'
mkdir -p /home/hicooper/backend/tracker-service
mkdir -p /home/hicooper/backend/tracker-service/logs
mkdir -p /home/hicooper/backend/tracker-service/config
mkdir -p /home/hicooper/backend/tracker-service/pid
echo "Remote directories created"
EOF

    log_info "远程目录创建完成"
}

# 上传文件
upload_files() {
    log_info "上传 JAR 文件到远程服务器..."

    scp "${PROJECT_DIR}/target/${JAR_NAME}" \
        "${SERVER_USER}@${SERVER_IP}:${SERVER_PATH}/${JAR_NAME}"

    log_info "JAR 文件上传完成"

    # 上传配置文件（如果存在）
    if [ -f "${PROJECT_DIR}/src/main/resources/application.yml" ]; then
        log_info "上传配置文件..."
        scp "${PROJECT_DIR}/src/main/resources/application.yml" \
            "${SERVER_USER}@${SERVER_IP}:${SERVER_PATH}/config/application.yml"
    fi

    if [ -f "${PROJECT_DIR}/src/main/resources/application-prod.yml" ]; then
        scp "${PROJECT_DIR}/src/main/resources/application-prod.yml" \
            "${SERVER_USER}@${SERVER_IP}:${SERVER_PATH}/config/application-prod.yml"
    fi
}

# 停止旧服务
stop_old_service() {
    log_info "检查并停止旧服务..."

    ssh ${SERVER_USER}@${SERVER_IP} << 'EOF'
PID_FILE="/home/hicooper/backend/tracker-service/pid/tracker.pid"
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE")
    if kill -0 "$OLD_PID" 2>/dev/null; then
        echo "Stopping old service (PID: $OLD_PID)..."
        kill "$OLD_PID"
        sleep 3
        # 强制 kill 如果还没停
        if kill -0 "$OLD_PID" 2>/dev/null; then
            kill -9 "$OLD_PID" 2>/dev/null || true
        fi
        echo "Old service stopped"
    else
        echo "Old service not running"
    fi
    rm -f "$PID_FILE"
else
    # 尝试通过端口查找
    PID=$(lsof -ti:8081 2>/dev/null || true)
    if [ -n "$PID" ]; then
        echo "Stopping service on port 8081 (PID: $PID)..."
        kill "$PID" 2>/dev/null || true
        sleep 2
    fi
    echo "No running service found"
fi
EOF

    log_info "旧服务已停止"
}

# 启动新服务
start_new_service() {
    log_info "启动新服务..."

    ssh ${SERVER_USER}@${SERVER_IP} << 'EOF'
cd /home/hicooper/backend/tracker-service

# 启动脚本
nohup java -Xms512m -Xmx1024m \
    -Dspring.profiles.active=prod \
    -Dserver.port=8081 \
    -Dlogging.file.path=./logs \
    -jar tracker-service-1.0.0-SNAPSHOT.jar \
    > ./logs/stdout.log 2>&1 &

echo $! > /home/hicooper/backend/tracker-service/pid/tracker.pid
echo "Service started with PID: $(cat /home/hicooper/backend/tracker-service/pid/tracker.pid)"
EOF

    log_info "服务启动命令已执行"
}

# 验证服务状态
verify_service() {
    log_info "等待服务启动并验证..."

    # 等待服务启动
    sleep 10

    # 检查健康状态
    HTTP_CODE=$(ssh ${SERVER_USER}@${SERVER_IP} \
        "curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/actuator/health" 2>/dev/null || echo "000")

    if [ "$HTTP_CODE" = "200" ]; then
        log_info "服务启动成功！健康检查通过 (HTTP $HTTP_CODE)"
    else
        log_warn "服务可能未完全启动 (HTTP $HTTP_CODE)"
        log_warn "请手动检查: ssh ${SERVER_USER}@${SERVER_IP}"
        log_warn "查看日志: tail -f ${SERVER_PATH}/logs/stdout.log"
    fi
}

# 显示使用说明
show_usage() {
    echo ""
    echo "============================================"
    echo "  部署完成！"
    echo "============================================"
    echo ""
    echo "远程服务器: ${SERVER_USER}@${SERVER_IP}"
    echo "部署路径:   ${SERVER_PATH}"
    echo ""
    echo "常用命令:"
    echo "  SSH:       ssh ${SERVER_USER}@${SERVER_IP}"
    echo "  查看日志:  tail -f ${SERVER_PATH}/logs/stdout.log"
    echo "  健康检查:  curl http://localhost:8081/actuator/health"
    echo "  停止服务:  kill \$(cat ${SERVER_PATH}/pid/tracker.pid)"
    echo "  重启服务:  bash ${SERVER_PATH}/deploy.sh"
    echo ""
}

# 主流程
main() {
    echo ""
    echo "============================================"
    echo "  GateFlow Tracker Service 部署脚本"
    echo "============================================"
    echo ""

    check_prerequisites
    build_package
    create_remote_dirs
    stop_old_service
    upload_files
    start_new_service
    verify_service
    show_usage
}

# 执行主流程
main "$@"