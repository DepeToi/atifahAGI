# atifah-gateway 独立镜像：构建上下文为本模块目录，只打包本模块 target/ 下预先构建好的可执行 jar。
#
# 前置：在仓库根目录执行 mvn clean package -DskipTests
# 构建（在模块目录执行）：docker build -t atifah/gateway:local .
# 国内网络拉取基础镜像受限时可覆盖镜像源：
#   docker build --build-arg BASE_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:21-jre-jammy \
#     -t atifah/gateway:local .
# 统一编排见仓库 deploy/docker-compose/docker-compose.app.yml。

ARG BASE_IMAGE=eclipse-temurin:21-jre-jammy
FROM ${BASE_IMAGE}

# 应用业务端口，同时用于 EXPOSE 与容器健康检查
ARG APP_PORT=8080
# 管理端点独立监听，绝不与业务入口共用端口
ARG MANAGEMENT_PORT=9090

ENV TZ=Asia/Shanghai \
    APP_PORT=${APP_PORT} \
    MANAGEMENT_PORT=${MANAGEMENT_PORT} \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

# 容器以宿主机 UID 运行时没有对应 passwd 记录，user.home 会落到 /；/tmp 保证 Nacos 快照与 RocketMQ 客户端日志可写
ENV HOME=/tmp

# 非 root 运行；基础镜像为 Ubuntu jammy，已自带 bash
RUN groupadd --system atifah \
    && useradd --system --gid atifah --home-dir /app --shell /usr/sbin/nologin atifah \
    && mkdir -p /app \
    && chown atifah:atifah /app

WORKDIR /app
# *.jar.original 与 target 下的 class 不会匹配；若存在多个 *.jar，COPY 会失败，请先清理
COPY --chown=atifah:atifah target/*.jar /app/app.jar

USER atifah
EXPOSE ${APP_PORT} ${MANAGEMENT_PORT}

# 仅探测端口可连通，不依赖 curl/wget；详细健康状态通过受控管理端口查看，
# 且管理端口不得随业务入口发布到公网。
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/$APP_PORT' || exit 1

# 镜像默认以 container profile 启动（中间件地址来自 application-container.yaml）；
# 容器内必须再显式声明环境：本地/沙箱追加 --spring.profiles.active=local，生产追加 =prod；
# 仅带 container 时安全护栏按 fail-closed 拒绝启动（多个 --spring.profiles.active 会合并）。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar --spring.profiles.active=container \"$@\"", "atifah-app"]
